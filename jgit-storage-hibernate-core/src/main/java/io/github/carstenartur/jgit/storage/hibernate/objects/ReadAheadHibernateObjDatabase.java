/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.objects.BoundedInlinePayloadCache.Identity;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedPackExtensionStore.CommitResult;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedPackExtensionStore.CommittedExtension;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
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
 * identifiers, sizes, storage modes and logical JGit pack metadata. Publication uses an atomic
 * generation check, so a scan that started before a successful pack mutation cannot overwrite the
 * subsequent invalidation or return a stale list after that mutation. A fair repository-instance-
 * local read/write lock prevents scans from reading the database while this instance has an
 * uncommitted pack replacement in progress. Chunked files can then open without repeating the
 * metadata query.
 *
 * <p>Inline payload bytes remain outside the generation catalog. Only newly staged inline PACK and
 * Reftable bytes may enter a hard-bounded 512-KiB repository-instance handoff after their database
 * publication commits. Historical database-loaded arrays, IDX files and auxiliary extensions are
 * never retained. An authoritative catalog scan preserves a local payload only when pack name,
 * extension, generated row ID, persisted size and inline storage mode still match exactly.
 *
 * <p>Successful local publication keeps JGit's normal {@code clearCache()} and event semantics. When
 * the previous catalog and the returned publication metadata are both complete, the exact committed
 * row metadata is merged into a one-shot local scan result before the cache is cleared. JGit's
 * resulting scan consumes that snapshot without a database transaction, then continues with its
 * native {@code addPack()} or {@code addReftable()} update. An incomplete catalog or a legacy
 * publication without exact row metadata never claims authority and falls back to a full database
 * scan.
 */
public final class ReadAheadHibernateObjDatabase extends HibernateObjDatabase {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;
  private final StorageByteCounters storageByteCounters;
  private final StagedPackExtensionStore stagedExtensions;
  private final PackFileReadCounters packFileReadCounters;
  private final BoundedInlinePayloadCache inlinePayloadCache = new BoundedInlinePayloadCache();
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
    this.storageByteCounters = StorageByteCounters.from(sessionFactory);
    this.stagedExtensions =
        new StagedPackExtensionStore(repositoryName, transactionContext, storageByteCounters);
    this.packFileReadCounters = PackFileReadCounters.from(sessionFactory);
  }

  /**
   * Reconstruct stored pack descriptions with sizes and logical JGit metadata.
   *
   * <p>Ordinary object reads can discover an unknown size lazily from the readable channel. JGit's
   * upload-pack copy-as-is path needs extension sizes before it copies compressed object
   * representations. Pack lookup, Reftable ordering and maintenance additionally use source,
   * modification time, object counts and update indexes. Persisting and restoring these fields keeps
   * repository reopen from degrading every pack to an undated {@code INSERT} description.
   */
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
          // Another scan published the same committed generation first. Both query results describe
          // the same mutation-free generation, so this result is equally valid for JGit's pack list.
          return loaded.descriptions();
        }
        // A pack mutation crossed this scan. Re-read so neither the optimization catalog nor JGit's
        // pack-list cache can be populated from the older committed generation.
      }
    } finally {
      readLock.unlock();
    }
  }

  private PackCatalog loadPackCatalog() throws IOException {
    PackCatalog catalog =
        transactionContext.execute(
            session -> {
              List<Object[]> rows =
                  session
                      .createQuery(
                          "SELECT p.id, p.packName, p.packExtension, p.fileSize, "
                              + "CASE WHEN p.data IS NULL THEN 0 ELSE 1 END, "
                              + "p.packSource, p.lastModified, p.objectCount, p.deltaCount, "
                              + "p.indexVersion, p.minUpdateIndex, p.maxUpdateIndex, "
                              + "p.committedAt, p.createdAt "
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
                PackDescriptionMetadata metadata =
                    PackDescriptionMetadata.fromStoredValues(
                        (String) row[5],
                        longValue(row[6]),
                        longValue(row[7]),
                        longValue(row[8]),
                        integerValue(row[9]),
                        longValue(row[10]),
                        longValue(row[11]),
                        (Instant) row[12],
                        (Instant) row[13]);
                extensions.put(
                    new ExtensionKey(packName, extension),
                    new ExtensionSnapshot(packId, fileSize, inline, metadata));
              }
              Map<ExtensionKey, ExtensionSnapshot> immutableExtensions = Map.copyOf(extensions);
              return new PackCatalog(descriptionsFrom(immutableExtensions), immutableExtensions);
            });
    inlinePayloadCache.retainOnly(cacheableIdentities(catalog.extensions()));
    return catalog;
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
              name -> {
                DfsPackDescription created =
                    new DfsPackDescription(
                        getRepository().getDescription(),
                        name,
                        snapshot.metadata().packSource());
                snapshot.metadata().applyTo(created);
                return created;
              });
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
    if (snapshot != null) {
      if (!snapshot.inline()) {
        return new ReadAheadChunkedReadableChannel(
            sessionFactory, snapshot.packId(), snapshot.fileSize(), storageByteCounters);
      }
      if (isCacheableExtension(key.extension())) {
        byte[] cached = inlinePayloadCache.get(payloadIdentity(key, snapshot));
        if (cached != null) {
          return new InlineReadableChannel(cached);
        }
      }
    }
    return openFileFromDatabase(description, extension);
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
                return new DatabaseOpenResult(
                    (Long) row[0], ((Number) row[1]).longValue(), (byte[]) row[2]);
              });
      packFileReadCounters.record(extension, result.inline());
      if (result.inline()) {
        storageByteCounters.recordDatabasePayloadBytesRead(result.inlineData().length);
        return new InlineReadableChannel(result.inlineData());
      }
      return new ReadAheadChunkedReadableChannel(
          sessionFactory, result.packId(), result.fileSize(), storageByteCounters);
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
        // DfsPackParser invokes rollbackPack after a publication error, but other supported JGit
        // paths such as Reftable publication may propagate commitPack failure directly. Clean local
        // staging here as well; rollback() is idempotent and retains the legacy database fallback.
        stagedExtensions.rollback(descriptions);
        restoreCatalogAfterFailedMutation(previousCatalog);
        throw publicationFailure;
      }
      removeReplacedPayloads(previousCatalog, replaces);
      completeCatalogMutation(commitResult);
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

  int inlinePayloadCacheEntryCount() {
    return inlinePayloadCache.entryCount();
  }

  long inlinePayloadCacheRetainedBytes() {
    return inlinePayloadCache.retainedBytes();
  }

  /**
   * Return monotone database fallback read attribution for this repository instance.
   *
   * @return current counters, or {@link PackFileReadMetrics#ZERO} when metrics are disabled
   */
  public PackFileReadMetrics packFileReadMetricsSnapshot() {
    return packFileReadCounters.snapshot();
  }

  /**
   * Return monotone staged-file, database-payload and read-ahead byte counters.
   *
   * @return current byte metrics, or {@link StorageByteMetrics#ZERO} when diagnostics are disabled
   */
  public StorageByteMetrics storageByteMetricsSnapshot() {
    return storageByteCounters.snapshot();
  }

  private CatalogState beginCatalogMutation(Collection<DfsPackDescription> replaces) {
    Set<String> replacedPackNames = packNames(replaces);
    return committedExtensionCatalog.getAndUpdate(
        current -> current.beginMutation(replacedPackNames));
  }

  private void removeReplacedPayloads(
      CatalogState previousCatalog, Collection<DfsPackDescription> replaces) {
    Set<String> replacedPackNames = packNames(replaces);
    if (replacedPackNames.isEmpty()) {
      return;
    }
    List<Identity> replacedIdentities = new ArrayList<>();
    for (Map.Entry<ExtensionKey, ExtensionSnapshot> entry :
        previousCatalog.extensions().entrySet()) {
      if (entry.getValue().inline()
          && isCacheableExtension(entry.getKey().extension())
          && replacedPackNames.contains(entry.getKey().packName())) {
        replacedIdentities.add(payloadIdentity(entry.getKey(), entry.getValue()));
      }
    }
    inlinePayloadCache.removeAll(replacedIdentities);
  }

  private static Set<String> packNames(Collection<DfsPackDescription> descriptions) {
    Set<String> names = new HashSet<>();
    if (descriptions != null) {
      for (DfsPackDescription description : descriptions) {
        names.add(baseName(description));
      }
    }
    return names;
  }

  private void completeCatalogMutation(CommitResult commitResult) {
    Map<ExtensionKey, ExtensionSnapshot> additions = new LinkedHashMap<>();
    List<CachedPayload> payloads = new ArrayList<>();
    for (CommittedExtension committed : commitResult.committedExtensions()) {
      ExtensionKey key = new ExtensionKey(committed.packName(), committed.extension());
      ExtensionSnapshot snapshot =
          new ExtensionSnapshot(
              committed.packId(),
              committed.fileSize(),
              committed.inline(),
              committed.metadata());
      additions.put(key, snapshot);
      if (committed.inline() && isCacheableExtension(committed.extension())) {
        payloads.add(new CachedPayload(payloadIdentity(key, snapshot), committed.inlineData()));
      }
    }

    committedExtensionCatalog.updateAndGet(
        current -> {
          LinkedHashMap<ExtensionKey, ExtensionSnapshot> extensions =
              new LinkedHashMap<>(current.extensions());
          extensions.putAll(additions);
          return current.completeMutation(extensions, commitResult.completeMetadata());
        });
    for (CachedPayload payload : payloads) {
      inlinePayloadCache.put(payload.identity(), payload.data());
    }
  }

  private void restoreCatalogAfterFailedMutation(CatalogState previousCatalog) {
    committedExtensionCatalog.updateAndGet(
        current -> previousCatalog.withGeneration(current.generation() + 1));
  }

  private static Set<Identity> cacheableIdentities(
      Map<ExtensionKey, ExtensionSnapshot> extensions) {
    Set<Identity> identities = new HashSet<>();
    for (Map.Entry<ExtensionKey, ExtensionSnapshot> entry : extensions.entrySet()) {
      if (entry.getValue().inline() && isCacheableExtension(entry.getKey().extension())) {
        identities.add(payloadIdentity(entry.getKey(), entry.getValue()));
      }
    }
    return Set.copyOf(identities);
  }

  private static boolean isCacheableExtension(String extension) {
    return PackExt.PACK.getExtension().equals(extension)
        || PackExt.REFTABLE.getExtension().equals(extension);
  }

  private static Identity payloadIdentity(ExtensionKey key, ExtensionSnapshot snapshot) {
    return new Identity(key.packName(), key.extension(), snapshot.packId(), snapshot.fileSize());
  }

  private static Long longValue(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Integer integerValue(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private record ExtensionKey(String packName, String extension) {}

  private record ExtensionSnapshot(
      Long packId, long fileSize, boolean inline, PackDescriptionMetadata metadata) {}

  private record CachedPayload(Identity identity, byte[] data) {}

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
          retained.put(entry.getKey(), entry.getValue());
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
  }

  private record PackCatalog(
      List<DfsPackDescription> descriptions, Map<ExtensionKey, ExtensionSnapshot> extensions) {}

  private record DatabaseOpenResult(Long packId, long fileSize, byte[] inlineData) {
    private boolean inline() {
      return inlineData != null;
    }
  }

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
    private final StorageByteCounters storageByteCounters;
    private final LinkedHashMap<Integer, CachedChunk> cachedChunks = new LinkedHashMap<>();
    private long position;
    private boolean open = true;
    private int readAheadBytes;

    ReadAheadChunkedReadableChannel(SessionFactory sessionFactory, Long packId, long fileSize) {
      this(sessionFactory, packId, fileSize, StorageByteCounters.disabled());
    }

    ReadAheadChunkedReadableChannel(
        SessionFactory sessionFactory,
        Long packId,
        long fileSize,
        StorageByteCounters storageByteCounters) {
      this.sessionFactory = sessionFactory;
      this.packId = packId;
      this.fileSize = fileSize;
      this.storageByteCounters = storageByteCounters;
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
        CachedChunk chunk = loadChunk(chunkIndex, lastChunkIndex);
        int count =
            (int)
                Math.min(
                    Math.min(destination.remaining(), chunk.data().length - offset),
                    fileSize - position);
        if (count <= 0) {
          throw new IOException(
              "Invalid chunk " + chunkIndex + " for pack " + packId + " at position " + position);
        }
        destination.put(chunk.data(), offset, count);
        storageByteCounters.recordReadAheadBytesConsumed(chunk.markConsumed(offset, count));
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

    private CachedChunk loadChunk(int chunkIndex, int lastChunkIndex) throws IOException {
      CachedChunk cached = cachedChunks.get(chunkIndex);
      if (cached != null) {
        return cached;
      }
      loadWindow(chunkIndex, lastChunkIndex);
      CachedChunk loaded = cachedChunks.get(chunkIndex);
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

      LinkedHashMap<Integer, CachedChunk> loadedChunks = new LinkedHashMap<>();
      long fetchedBytes = 0;
      int expectedChunkIndex = firstChunkIndex;
      try {
        for (Object[] row : rows) {
          int actualChunkIndex = ((Number) row[0]).intValue();
          if (actualChunkIndex != expectedChunkIndex) {
            throw new IOException("Missing chunk " + expectedChunkIndex + " for pack " + packId);
          }
          byte[] data = (byte[]) row[1];
          int declaredSize = ((Number) row[2]).intValue();
          storageByteCounters.recordDatabasePayloadBytesRead(data.length);
          storageByteCounters.recordReadAheadBytesFetched(data.length);
          fetchedBytes += data.length;
          validateChunk(actualChunkIndex, data, declaredSize);
          loadedChunks.put(
              actualChunkIndex, new CachedChunk(data, storageByteCounters.enabled()));
          expectedChunkIndex++;
        }
        if (expectedChunkIndex <= lastChunkIndex) {
          throw new IOException("Missing chunk " + expectedChunkIndex + " for pack " + packId);
        }
      } catch (IOException failure) {
        storageByteCounters.recordReadAheadOverfetchBytes(fetchedBytes);
        throw failure;
      }

      discardCachedWindow();
      cachedChunks.putAll(loadedChunks);
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

    private void discardCachedWindow() {
      if (storageByteCounters.enabled()) {
        for (CachedChunk chunk : cachedChunks.values()) {
          storageByteCounters.recordReadAheadOverfetchBytes(chunk.unconsumedBytes());
        }
      }
      cachedChunks.clear();
    }

    @Override
    public void close() {
      if (!open) {
        return;
      }
      open = false;
      discardCachedWindow();
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
          discardCachedWindow();
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

    private static final class CachedChunk {
      private final byte[] data;
      private final BitSet consumed;

      private CachedChunk(byte[] data, boolean trackConsumption) {
        this.data = data;
        this.consumed = trackConsumption ? new BitSet(data.length) : null;
      }

      private byte[] data() {
        return data;
      }

      private int markConsumed(int offset, int length) {
        if (consumed == null) {
          return 0;
        }
        int before = consumed.cardinality();
        consumed.set(offset, offset + length);
        return consumed.cardinality() - before;
      }

      private long unconsumedBytes() {
        return consumed == null ? 0 : data.length - consumed.cardinality();
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
