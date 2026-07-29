/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.objects.StagedPackExtensionStore.CommitResult;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedPackExtensionStore.CommittedExtension;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedPackExtensionStore.LocalInlinePayload;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.SessionFactory;

/**
 * Hibernate object database with bounded multi-chunk read-ahead for sequential pack access.
 *
 * <p>JGit communicates its desired read-ahead window through {@link
 * ReadableChannel#setReadAheadBytes(int)}. The original channel queried one database row and opened
 * one Hibernate session for every one MiB chunk. This implementation loads a bounded consecutive
 * window with one ordered query, then serves all chunks from the channel-local cache. Random seeks
 * still load only the requested bounded window, and no Hibernate session or JDBC connection is held
 * for the lifetime of the channel.
 *
 * <p>The writable channel reports the same one MiB alignment used by persisted chunks and readable
 * channels. This keeps JGit's write-time DFS block cache aligned with later reads after pack-list
 * invalidation and prevents stale blocks with a different alignment from being reused by copy-as-is
 * protocol transfers.
 *
 * <p>Completed unpublished extensions are retained in bounded JVM-local temporary files. JGit closes
 * every extension before it invokes {@code commitPack}; that callback persists all expected
 * extensions and makes them visible through one repository-locked Hibernate transaction. The base
 * implementation remains available for compatibility tests and legacy uncommitted rows.
 *
 * <p>Each successful pack-list scan publishes an immutable catalog of committed extension row
 * identifiers, sizes and storage modes. Publication uses an atomic generation check, so a scan that
 * started before a successful pack mutation cannot overwrite the subsequent invalidation or return a
 * stale list after that mutation. A fair repository-instance-local read/write lock prevents scans
 * from reading the database while this instance has an uncommitted pack replacement in progress.
 * Chunked files can then open without repeating the metadata query. Historical inline payload bytes
 * are deliberately not retained in the catalog.
 *
 * <p>Successful local publication keeps JGit's normal {@code clearCache()} and event semantics. When
 * the previous catalog and the returned publication metadata are both complete, exact committed row
 * metadata is merged into a one-shot local scan result before the cache is cleared. Newly staged
 * inline PACK and Reftable payloads may additionally be retained within the hard per-publication
 * budget and are atomically removed at their first matching open. An incomplete catalog, legacy
 * publication or authoritative database scan contains no local payload state.
 */
public final class ReadAheadHibernateObjDatabase extends HibernateObjDatabase {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;
  private final StagedPackExtensionStore stagedExtensions;
  private final PackFileReadCounters packFileReadCounters;
  private final AtomicReference<CatalogState> committedExtensionCatalog =
      new AtomicReference<>(CatalogState.empty());
  private final ReentrantReadWriteLock catalogLifecycleLock = new ReentrantReadWriteLock(true);

  public ReadAheadHibernateObjDatabase(
      DfsRepository repository,
      DfsReaderOptions options,
      SessionFactory sessionFactory,
      String repositoryName,
      HibernateTransactionContext transactionContext) {
    super(repository, options, sessionFactory, repositoryName, transactionContext);
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
    this.transactionContext = transactionContext;
    this.stagedExtensions = new StagedPackExtensionStore(repositoryName, transactionContext);
    this.packFileReadCounters = PackFileReadCounters.from(sessionFactory);
  }

  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    Lock readLock = catalogLifecycleLock.readLock();
    readLock.lock();
    try {
      while (true) {
        CatalogState observedCatalog = committedExtensionCatalog.get();
        if (observedCatalog.localScanAvailable()) {
          CatalogState consumed = observedCatalog.consumeLocalScan();
          if (committedExtensionCatalog.compareAndSet(observedCatalog, consumed)) {
            return descriptionsFrom(observedCatalog.extensions());
          }
          continue;
        }

        PackCatalog loaded = loadPackCatalog();
        CatalogState loadedCatalog =
            new CatalogState(observedCatalog.generation(), true, false, loaded.extensions());
        if (committedExtensionCatalog.compareAndSet(observedCatalog, loadedCatalog)) {
          return loaded.descriptions();
        }
        if (committedExtensionCatalog.get().generation() == observedCatalog.generation()) {
          return loaded.descriptions();
        }
      }
    } finally {
      readLock.unlock();
    }
  }

  private PackCatalog loadPackCatalog() throws IOException {
    return transactionContext.execute(
        session -> {
          List<Object[]> rows =
              session
                  .createQuery(
                      "SELECT p.id, p.packName, p.packExtension, p.fileSize, "
                          + "CASE WHEN p.data IS NULL THEN 0 ELSE 1 END "
                          + "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                          + "AND p.committed = true",
                      Object[].class)
                  .setParameter("repo", repositoryName)
                  .getResultList();
          LinkedHashMap<ExtensionKey, ExtensionSnapshot> extensions = new LinkedHashMap<>();
          for (Object[] row : rows) {
            Long packId = (Long) row[0];
            String packName = (String) row[1];
            String extension = (String) row[2];
            long fileSize = ((Number) row[3]).longValue();
            boolean inline = ((Number) row[4]).intValue() != 0;
            extensions.put(
                new ExtensionKey(packName, extension),
                new ExtensionSnapshot(packId, fileSize, inline, PackSource.INSERT, null));
          }
          Map<ExtensionKey, ExtensionSnapshot> immutableExtensions = Map.copyOf(extensions);
          return new PackCatalog(descriptionsFrom(immutableExtensions), immutableExtensions);
        });
  }

  private List<DfsPackDescription> descriptionsFrom(
      Map<ExtensionKey, ExtensionSnapshot> extensions) {
    LinkedHashMap<String, DfsPackDescription> descriptions = new LinkedHashMap<>();
    for (Map.Entry<ExtensionKey, ExtensionSnapshot> entry : extensions.entrySet()) {
      ExtensionKey key = entry.getKey();
      ExtensionSnapshot snapshot = entry.getValue();
      DfsPackDescription description =
          descriptions.computeIfAbsent(
              key.packName(),
              name ->
                  new DfsPackDescription(
                      getRepository().getDescription(), name, snapshot.packSource()));
      for (PackExt packExtension : PackExt.values()) {
        if (packExtension.getExtension().equals(key.extension())) {
          description.addFileExt(packExtension);
          description.setFileSize(packExtension, snapshot.fileSize());
          break;
        }
      }
    }
    return List.copyOf(new ArrayList<>(descriptions.values()));
  }

  @Override
  protected ReadableChannel openFile(DfsPackDescription description, PackExt extension)
      throws FileNotFoundException, IOException {
    ExtensionKey key = new ExtensionKey(baseName(description), extension.getExtension());
    ExtensionSnapshot snapshot = committedExtensionCatalog.get().extensions().get(key);
    if (snapshot != null && !snapshot.inline()) {
      return new ReadAheadChunkedReadableChannel(
          sessionFactory, snapshot.packId(), snapshot.fileSize());
    }
    LocalInlinePayload localInlinePayload = consumeLocalInlinePayload(key);
    if (localInlinePayload != null) {
      return new InlineReadableChannel(localInlinePayload.transfer());
    }
    return openFileFromDatabase(description, extension);
  }

  private LocalInlinePayload consumeLocalInlinePayload(ExtensionKey key) {
    while (true) {
      CatalogState current = committedExtensionCatalog.get();
      ExtensionSnapshot snapshot = current.extensions().get(key);
      if (snapshot == null || snapshot.localInlinePayload() == null) {
        return null;
      }
      LinkedHashMap<ExtensionKey, ExtensionSnapshot> extensions =
          new LinkedHashMap<>(current.extensions());
      extensions.put(key, snapshot.withoutLocalInlinePayload());
      CatalogState consumed = current.withExtensions(extensions);
      if (committedExtensionCatalog.compareAndSet(current, consumed)) {
        return snapshot.localInlinePayload();
      }
    }
  }

  private ReadableChannel openFileFromDatabase(
      DfsPackDescription description, PackExt extension) throws IOException {
    try {
      DatabaseOpenResult result =
          transactionContext.execute(
              session -> {
                Object[] row =
                    session
                        .createQuery(
                            "SELECT p.id, p.fileSize, p.data FROM GitPackEntity p "
                                + "WHERE p.repositoryName = :repo AND p.packName = :name "
                                + "AND p.packExtension = :ext AND p.committed = true",
                            Object[].class)
                        .setParameter("repo", repositoryName)
                        .setParameter("name", baseName(description))
                        .setParameter("ext", extension.getExtension())
                        .uniqueResult();
                if (row == null) {
                  throw new FileNotFoundException(description.getFileName(extension));
                }
                Long packId = (Long) row[0];
                long fileSize = ((Number) row[1]).longValue();
                byte[] inlineData = (byte[]) row[2];
                if (inlineData != null) {
                  return new DatabaseOpenResult(new InlineReadableChannel(inlineData), true);
                }
                return new DatabaseOpenResult(
                    new ReadAheadChunkedReadableChannel(sessionFactory, packId, fileSize), false);
              });
      packFileReadCounters.record(extension, result.inline());
      return result.channel();
    } catch (FileNotFoundException missing) {
      packFileReadCounters.recordMissing();
      throw missing;
    }
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    return new AlignedDfsOutputStream(stagedExtensions.open(description, extension));
  }

  @Override
  protected void commitPackImpl(
      Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replaces)
      throws IOException {
    Lock writeLock = catalogLifecycleLock.writeLock();
    writeLock.lock();
    try {
      CatalogState previousCatalog = beginCatalogMutation(replaces);
      CommitResult commitResult;
      try {
        commitResult = stagedExtensions.commit(descriptions, replaces);
      } catch (IOException | RuntimeException publicationFailure) {
        stagedExtensions.rollback(descriptions);
        restoreCatalogAfterFailedMutation(previousCatalog);
        throw publicationFailure;
      }
      completeCatalogMutation(descriptions, commitResult);
      clearCache();
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
    stagedExtensions.rollback(descriptions);
  }

  int stagedExtensionCount() {
    return stagedExtensions.stagedExtensionCount();
  }

  int committedExtensionCatalogSize() {
    return committedExtensionCatalog.get().extensions().size();
  }

  boolean localPackListScanAvailable() {
    return committedExtensionCatalog.get().localScanAvailable();
  }

  int localInlinePayloadBytes() {
    return committedExtensionCatalog.get().extensions().values().stream()
        .map(ExtensionSnapshot::localInlinePayload)
        .filter(payload -> payload != null)
        .mapToInt(LocalInlinePayload::size)
        .sum();
  }

  public PackFileReadMetrics packFileReadMetricsSnapshot() {
    return packFileReadCounters.snapshot();
  }

  private CatalogState beginCatalogMutation(Collection<DfsPackDescription> replaces) {
    Set<String> replacedPackNames = new HashSet<>();
    if (replaces != null) {
      for (DfsPackDescription replaced : replaces) {
        replacedPackNames.add(baseName(replaced));
      }
    }
    return committedExtensionCatalog.getAndUpdate(
        current -> current.beginMutation(replacedPackNames));
  }

  private void completeCatalogMutation(
      Collection<DfsPackDescription> descriptions, CommitResult commitResult) {
    Map<String, PackSource> packSources = new LinkedHashMap<>();
    for (DfsPackDescription description : descriptions) {
      packSources.put(baseName(description), description.getPackSource());
    }
    committedExtensionCatalog.updateAndGet(
        current -> {
          LinkedHashMap<ExtensionKey, ExtensionSnapshot> extensions =
              new LinkedHashMap<>(current.extensions());
          for (CommittedExtension committed : commitResult.committedExtensions()) {
            PackSource source = packSources.getOrDefault(committed.packName(), PackSource.INSERT);
            LocalInlinePayload localInlinePayload =
                commitResult.completeMetadata() ? committed.localInlinePayload() : null;
            extensions.put(
                new ExtensionKey(committed.packName(), committed.extension()),
                new ExtensionSnapshot(
                    committed.packId(),
                    committed.fileSize(),
                    committed.inline(),
                    source,
                    localInlinePayload));
          }
          return current.completeMutation(extensions, commitResult.completeMetadata());
        });
  }

  private void restoreCatalogAfterFailedMutation(CatalogState previousCatalog) {
    committedExtensionCatalog.updateAndGet(
        current -> previousCatalog.withGeneration(current.generation() + 1));
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private record ExtensionKey(String packName, String extension) {}

  private record ExtensionSnapshot(
      Long packId,
      long fileSize,
      boolean inline,
      PackSource packSource,
      LocalInlinePayload localInlinePayload) {
    private ExtensionSnapshot withoutLocalInlinePayload() {
      return localInlinePayload == null
          ? this
          : new ExtensionSnapshot(packId, fileSize, inline, packSource, null);
    }
  }

  private record CatalogState(
      long generation,
      boolean complete,
      boolean localScanAvailable,
      Map<ExtensionKey, ExtensionSnapshot> extensions) {
    private CatalogState {
      extensions = Map.copyOf(extensions);
      localScanAvailable = complete && localScanAvailable;
    }

    private static CatalogState empty() {
      return new CatalogState(0, false, false, Map.of());
    }

    private CatalogState consumeLocalScan() {
      return new CatalogState(generation, complete, false, extensions);
    }

    private CatalogState beginMutation(Set<String> replacedPackNames) {
      LinkedHashMap<ExtensionKey, ExtensionSnapshot> retained = new LinkedHashMap<>();
      for (Map.Entry<ExtensionKey, ExtensionSnapshot> entry : extensions.entrySet()) {
        if (!replacedPackNames.contains(entry.getKey().packName())) {
          retained.put(entry.getKey(), entry.getValue().withoutLocalInlinePayload());
        }
      }
      return new CatalogState(generation + 1, complete, false, retained);
    }

    private CatalogState completeMutation(
        Map<ExtensionKey, ExtensionSnapshot> updatedExtensions, boolean completeMetadata) {
      boolean completedCatalog = complete && completeMetadata;
      return new CatalogState(
          generation + 1, completedCatalog, completedCatalog, updatedExtensions);
    }

    private CatalogState withGeneration(long newGeneration) {
      return new CatalogState(newGeneration, complete, localScanAvailable, extensions);
    }

    private CatalogState withExtensions(Map<ExtensionKey, ExtensionSnapshot> newExtensions) {
      return new CatalogState(generation, complete, localScanAvailable, newExtensions);
    }
  }

  private record PackCatalog(
      List<DfsPackDescription> descriptions, Map<ExtensionKey, ExtensionSnapshot> extensions) {}

  private record DatabaseOpenResult(ReadableChannel channel, boolean inline) {}

  private static final class PackFileReadCounters {
    private static final int PACK_INLINE = 0;
    private static final int PACK_CHUNKED = 1;
    private static final int INDEX_INLINE = 2;
    private static final int INDEX_CHUNKED = 3;
    private static final int REFTABLE_INLINE = 4;
    private static final int REFTABLE_CHUNKED = 5;
    private static final int OTHER_INLINE = 6;
    private static final int OTHER_CHUNKED = 7;
    private static final int MISSING = 8;
    private static final int COUNTER_COUNT = 9;

    private final LongAdder[] counters;

    private PackFileReadCounters(boolean enabled) {
      if (!enabled) {
        counters = null;
        return;
      }
      counters = new LongAdder[COUNTER_COUNT];
      for (int index = 0; index < counters.length; index++) {
        counters[index] = new LongAdder();
      }
    }

    private static PackFileReadCounters from(SessionFactory sessionFactory) {
      Object configured =
          sessionFactory
              .getProperties()
              .get(HibernateTransactionContext.METRICS_ENABLED_PROPERTY);
      boolean enabled = configured != null && Boolean.parseBoolean(configured.toString());
      return new PackFileReadCounters(enabled);
    }

    private void record(PackExt extension, boolean inline) {
      if (counters == null) {
        return;
      }
      int counter;
      if (extension == PackExt.PACK) {
        counter = inline ? PACK_INLINE : PACK_CHUNKED;
      } else if (extension == PackExt.INDEX) {
        counter = inline ? INDEX_INLINE : INDEX_CHUNKED;
      } else if (extension == PackExt.REFTABLE) {
        counter = inline ? REFTABLE_INLINE : REFTABLE_CHUNKED;
      } else {
        counter = inline ? OTHER_INLINE : OTHER_CHUNKED;
      }
      counters[counter].increment();
    }

    private void recordMissing() {
      if (counters != null) {
        counters[MISSING].increment();
      }
    }

    private PackFileReadMetrics snapshot() {
      if (counters == null) {
        return PackFileReadMetrics.ZERO;
      }
      return new PackFileReadMetrics(
          counters[PACK_INLINE].sum(),
          counters[PACK_CHUNKED].sum(),
          counters[INDEX_INLINE].sum(),
          counters[INDEX_CHUNKED].sum(),
          counters[REFTABLE_INLINE].sum(),
          counters[REFTABLE_CHUNKED].sum(),
          counters[OTHER_INLINE].sum(),
          counters[OTHER_CHUNKED].sum(),
          counters[MISSING].sum());
    }
  }

  private static final class AlignedDfsOutputStream extends DfsOutputStream {
    private final DfsOutputStream delegate;

    private AlignedDfsOutputStream(DfsOutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int blockSize() {
      return PACK_CHUNK_SIZE;
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
      delegate.write(source, offset, length);
    }

    @Override
    public int read(long position, ByteBuffer destination) throws IOException {
      return delegate.read(position, destination);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  static final class ReadAheadChunkedReadableChannel implements ReadableChannel {
    static final int MAX_PREFETCH_CHUNKS = 16;

    private final SessionFactory sessionFactory;
    private final Long packId;
    private final long fileSize;
    private final LinkedHashMap<Integer, byte[]> cachedChunks = new LinkedHashMap<>();
    private long position;
    private boolean open = true;
    private int readAheadBytes;

    ReadAheadChunkedReadableChannel(SessionFactory sessionFactory, Long packId, long fileSize) {
      this.sessionFactory = sessionFactory;
      this.packId = packId;
      this.fileSize = fileSize;
    }

    @Override
    public int read(ByteBuffer destination) throws IOException {
      ensureOpen();
      if (position >= fileSize) {
        return -1;
      }
      int total = 0;
      while (destination.hasRemaining() && position < fileSize) {
        int chunkIndex = Math.toIntExact(position / PACK_CHUNK_SIZE);
        int offset = (int) (position % PACK_CHUNK_SIZE);
        int lastChunkIndex = lastChunkToPrefetch(chunkIndex, destination.remaining());
        byte[] chunk = loadChunk(chunkIndex, lastChunkIndex);
        int count =
            (int)
                Math.min(
                    Math.min(destination.remaining(), chunk.length - offset), fileSize - position);
        if (count <= 0) {
          throw new IOException(
              "Invalid chunk " + chunkIndex + " for pack " + packId + " at position " + position);
        }
        destination.put(chunk, offset, count);
        position += count;
        total += count;
      }
      return total;
    }

    private int lastChunkToPrefetch(int firstChunkIndex, int requestedBytes) {
      long windowBytes = Math.max(1L, (long) requestedBytes + readAheadBytes);
      long endExclusive = Math.min(fileSize, position + windowBytes);
      int requestedLast = Math.toIntExact((endExclusive - 1) / PACK_CHUNK_SIZE);
      return Math.min(requestedLast, firstChunkIndex + MAX_PREFETCH_CHUNKS - 1);
    }

    private byte[] loadChunk(int chunkIndex, int lastChunkIndex) throws IOException {
      byte[] cached = cachedChunks.get(chunkIndex);
      if (cached != null) {
        return cached;
      }
      loadWindow(chunkIndex, lastChunkIndex);
      byte[] loaded = cachedChunks.get(chunkIndex);
      if (loaded == null) {
        throw new IOException("Missing chunk " + chunkIndex + " for pack " + packId);
      }
      return loaded;
    }

    private void loadWindow(int firstChunkIndex, int lastChunkIndex) throws IOException {
      List<Object[]> rows;
      try (var session = sessionFactory.openSession()) {
        rows =
            session
                .createQuery(
                    "SELECT c.chunkIndex, c.data, c.chunkSize FROM GitPackChunkEntity c "
                        + "WHERE c.packId = :packId AND c.chunkIndex BETWEEN :first AND :last "
                        + "ORDER BY c.chunkIndex",
                    Object[].class)
                .setParameter("packId", packId)
                .setParameter("first", firstChunkIndex)
                .setParameter("last", lastChunkIndex)
                .getResultList();
      }

      cachedChunks.clear();
      int expectedChunkIndex = firstChunkIndex;
      for (Object[] row : rows) {
        int actualChunkIndex = ((Number) row[0]).intValue();
        if (actualChunkIndex != expectedChunkIndex) {
          throw new IOException("Missing chunk " + expectedChunkIndex + " for pack " + packId);
        }
        byte[] data = (byte[]) row[1];
        int declaredSize = ((Number) row[2]).intValue();
        validateChunk(actualChunkIndex, data, declaredSize);
        cachedChunks.put(actualChunkIndex, data);
        expectedChunkIndex++;
      }
      if (expectedChunkIndex <= lastChunkIndex) {
        throw new IOException("Missing chunk " + expectedChunkIndex + " for pack " + packId);
      }
    }

    private void validateChunk(int chunkIndex, byte[] data, int declaredSize) throws IOException {
      if (data.length != declaredSize || declaredSize > PACK_CHUNK_SIZE) {
        throw new IOException(
            "Corrupt chunk "
                + chunkIndex
                + " for pack "
                + packId
                + ": declared="
                + declaredSize
                + ", actual="
                + data.length);
      }
    }

    @Override
    public void close() {
      open = false;
      cachedChunks.clear();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public void position(long newPosition) {
      if (newPosition < 0 || newPosition > fileSize) {
        throw new IllegalArgumentException(
            "position must be between 0 and " + fileSize + ": " + newPosition);
      }
      if (newPosition < fileSize) {
        int targetChunkIndex = Math.toIntExact(newPosition / PACK_CHUNK_SIZE);
        if (!cachedChunks.containsKey(targetChunkIndex)) {
          cachedChunks.clear();
        }
      }
      position = newPosition;
    }

    @Override
    public long size() {
      return fileSize;
    }

    @Override
    public int blockSize() {
      return PACK_CHUNK_SIZE;
    }

    @Override
    public void setReadAheadBytes(int requestedReadAheadBytes) {
      readAheadBytes = Math.max(0, requestedReadAheadBytes);
    }

    private void ensureOpen() throws IOException {
      if (!open) {
        throw new IOException("Pack channel is closed");
      }
    }
  }

  private static final class InlineReadableChannel implements ReadableChannel {
    private final byte[] data;
    private int position;
    private boolean open = true;

    private InlineReadableChannel(byte[] data) {
      this.data = data;
    }

    @Override
    public int read(ByteBuffer destination) throws IOException {
      ensureOpen();
      int count = Math.min(destination.remaining(), data.length - position);
      if (count <= 0) {
        return -1;
      }
      destination.put(data, position, count);
      position += count;
      return count;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public void position(long newPosition) {
      if (newPosition < 0 || newPosition > data.length) {
        throw new IllegalArgumentException(
            "position must be between 0 and " + data.length + ": " + newPosition);
      }
      position = Math.toIntExact(newPosition);
    }

    @Override
    public long size() {
      return data.length;
    }

    @Override
    public int blockSize() {
      return PACK_CHUNK_SIZE;
    }

    @Override
    public void setReadAheadBytes(int readAheadBytes) {
      // Inline data is already available in memory.
    }

    private void ensureOpen() throws IOException {
      if (!open) {
        throw new IOException("Pack channel is closed");
      }
    }
  }
}
