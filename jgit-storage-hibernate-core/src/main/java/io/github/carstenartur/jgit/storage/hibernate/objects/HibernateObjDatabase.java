/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * {@link DfsObjDatabase} backed by Hibernate-managed relational database rows.
 *
 * <p>Pack extensions are written to bounded temporary files that support JGit's required random
 * read-back while the stream remains open. Closing an extension stages that file in the repository
 * instance; no database row is created until JGit calls {@link #commitPackImpl(Collection,
 * Collection)}. All staged extensions for one logical pack are persisted and made visible in the
 * same repository-locked Hibernate transaction.
 *
 * <p>Small payloads are stored in the existing inline column to avoid an additional chunk row and
 * ORM round trip. Larger payloads are persisted as bounded one MiB chunks. Existing inline and
 * chunked rows, including uncommitted rows created by earlier releases, remain compatible.
 *
 * <p>A failed publication removes the JVM-local staged files. Legacy abandoned uncommitted database
 * rows remain recoverable through {@code PackStorageMaintenance}. A process crash may leave ordinary
 * operating-system temporary files, but cannot leave a new partially persisted database pack under
 * this staging model.
 *
 * <p>Shallow repositories are not supported. A non-empty shallow boundary is rejected explicitly
 * instead of being retained only in memory and silently lost on restart.
 */
public class HibernateObjDatabase extends DfsObjDatabase {

  static final int PACK_CHUNK_SIZE = 1024 * 1024;
  static final int INLINE_PAYLOAD_THRESHOLD = 256 * 1024;
  private static final int CHUNK_BATCH_SIZE = 8;
  private static final System.Logger LOGGER =
      System.getLogger(HibernateObjDatabase.class.getName());

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;
  private final Map<StagedExtensionKey, StagedPackExtension> stagedExtensions =
      new ConcurrentHashMap<>();

  /**
   * Create an object database.
   *
   * @param repository owning repository
   * @param options DFS reader options
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   * @param transactionContext repository-scoped transaction context
   */
  public HibernateObjDatabase(
      DfsRepository repository,
      DfsReaderOptions options,
      SessionFactory sessionFactory,
      String repositoryName,
      HibernateTransactionContext transactionContext) {
    super(repository, options);
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
    this.transactionContext = transactionContext;
  }

  protected static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    return transactionContext.execute(
        StorageOperationKind.PACK_METADATA_READ,
        session -> {
          List<Object[]> rows =
              session
                  .createQuery(
                      "SELECT p.packName, p.packExtension FROM GitPackEntity p "
                          + "WHERE p.repositoryName = :repo AND p.committed = true",
                      Object[].class)
                  .setParameter("repo", repositoryName)
                  .getResultList();
          LinkedHashMap<String, DfsPackDescription> descriptions = new LinkedHashMap<>();
          for (Object[] row : rows) {
            String packName = (String) row[0];
            String extension = (String) row[1];
            DfsPackDescription description =
                descriptions.computeIfAbsent(
                    packName,
                    name ->
                        new DfsPackDescription(
                            getRepository().getDescription(), name, PackSource.INSERT));
            for (PackExt packExtension : PackExt.values()) {
              if (packExtension.getExtension().equals(extension)) {
                description.addFileExt(packExtension);
                break;
              }
            }
          }
          return new ArrayList<>(descriptions.values());
        });
  }

  @Override
  protected DfsPackDescription newPack(PackSource source) {
    String name = "pack-" + source.name().toLowerCase() + "-" + UUID.randomUUID();
    return new DfsPackDescription(getRepository().getDescription(), name, source);
  }

  @Override
  protected void commitPackImpl(
      Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replaces)
      throws IOException {
    try {
      transactionContext.executeWithRepositoryLock(
          StorageOperationKind.PACK_PUBLICATION,
          repositoryName,
          session -> {
            if (replaces != null) {
              for (DfsPackDescription replace : replaces) {
                deletePackRows(session, repositoryName, baseName(replace));
              }
            }
            Instant committedAt = Instant.now();
            for (DfsPackDescription description : descriptions) {
              publishDescription(session, description, committedAt);
            }
            return null;
          });
    } catch (IOException | RuntimeException failure) {
      discardStagedExtensions(descriptions);
      throw failure;
    }
    discardStagedExtensions(descriptions);
    clearCache();
  }

  private void publishDescription(
      Session session, DfsPackDescription description, Instant committedAt) throws IOException {
    String packName = baseName(description);
    Map<String, StagedPackExtension> staged = stagedForPack(packName);
    if (staged.isEmpty()) {
      publishLegacyUncommittedRows(session, packName, committedAt);
      return;
    }

    List<String> expectedExtensions = new ArrayList<>();
    for (PackExt extension : PackExt.values()) {
      if (description.hasFileExt(extension)) {
        String extensionName = extension.getExtension();
        expectedExtensions.add(extensionName);
        StagedPackExtension payload = staged.get(extensionName);
        if (payload == null) {
          throw new IOException(
              "Missing staged extension " + packName + "." + extensionName);
        }
        persistCommittedExtension(session, payload, committedAt);
      }
    }
    if (expectedExtensions.size() != staged.size()) {
      throw new IOException(
          "Staged extensions do not match JGit pack description for "
              + packName
              + ": expected="
              + expectedExtensions
              + ", staged="
              + staged.keySet());
    }
  }

  private void publishLegacyUncommittedRows(Session session, String packName, Instant committedAt)
      throws IOException {
    int updated =
        session
            .createMutationQuery(
                "UPDATE GitPackEntity p SET p.committed = true, "
                    + "p.committedAt = :committedAt, p.writeToken = null, "
                    + "p.writeLeaseUntil = null WHERE p.repositoryName = :repo "
                    + "AND p.packName = :name AND p.committed = false")
            .setParameter("committedAt", committedAt)
            .setParameter("repo", repositoryName)
            .setParameter("name", packName)
            .executeUpdate();
    if (updated == 0) {
      throw new IOException("Cannot publish missing or already committed pack " + packName);
    }
  }

  private void persistCommittedExtension(
      Session session, StagedPackExtension staged, Instant committedAt) throws IOException {
    GitPackEntity entity = new GitPackEntity();
    entity.setRepositoryName(repositoryName);
    entity.setPackName(staged.packName());
    entity.setPackExtension(staged.packExtension());
    entity.setFileSize(staged.fileSize());
    entity.setCreatedAt(staged.createdAt());
    entity.setCommitted(true);
    entity.setCommittedAt(committedAt);
    entity.setWriteToken(null);
    entity.setWriteLeaseUntil(null);

    boolean inlinePayload = staged.fileSize() <= INLINE_PAYLOAD_THRESHOLD;
    entity.setData(inlinePayload ? staged.readInlinePayload() : null);
    session.persist(entity);
    session.flush();
    if (!inlinePayload) {
      staged.persistChunks(session, entity.getId());
    }
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
    discardStagedExtensions(descriptions);
    try {
      transactionContext.executeWithRepositoryLock(
          StorageOperationKind.PACK_ROLLBACK,
          repositoryName,
          session -> {
            for (DfsPackDescription description : descriptions) {
              deletePackRows(session, repositoryName, baseName(description));
            }
            return null;
          });
    } catch (IOException | RuntimeException ignored) {
      // Rollback is best-effort and must not mask the original JGit exception.
    }
  }

  private static void deletePackRows(Session session, String repositoryName, String packName) {
    List<Long> packIds =
        session
            .createQuery(
                "SELECT p.id FROM GitPackEntity p WHERE p.repositoryName = :repo "
                    + "AND p.packName = :name",
                Long.class)
            .setParameter("repo", repositoryName)
            .setParameter("name", packName)
            .getResultList();
    if (!packIds.isEmpty()) {
      session
          .createMutationQuery("DELETE FROM GitPackChunkEntity c WHERE c.packId IN :packIds")
          .setParameter("packIds", packIds)
          .executeUpdate();
      session
          .createMutationQuery("DELETE FROM GitPackEntity p WHERE p.id IN :packIds")
          .setParameter("packIds", packIds)
          .executeUpdate();
    }
  }

  @Override
  protected ReadableChannel openFile(DfsPackDescription description, PackExt extension)
      throws FileNotFoundException, IOException {
    return transactionContext.execute(
        StorageOperationKind.PACK_FILE_READ,
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
            return new ByteArrayReadableChannel(inlineData);
          }
          return new ChunkedReadableChannel(sessionFactory, packId, fileSize);
        });
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    return new HibernatePackOutputStream(
        baseName(description), extension.getExtension(), this::stageExtension);
  }

  private void stageExtension(StagedPackExtension staged) throws IOException {
    StagedExtensionKey key = new StagedExtensionKey(staged.packName(), staged.packExtension());
    StagedPackExtension existing = stagedExtensions.putIfAbsent(key, staged);
    if (existing != null) {
      staged.deleteBestEffort();
      throw new IOException(
          "Pack extension is already staged: " + staged.packName() + "." + staged.packExtension());
    }
  }

  private Map<String, StagedPackExtension> stagedForPack(String packName) {
    Map<String, StagedPackExtension> result = new LinkedHashMap<>();
    stagedExtensions.forEach(
        (key, value) -> {
          if (key.packName().equals(packName)) {
            result.put(key.packExtension(), value);
          }
        });
    return result;
  }

  private void discardStagedExtensions(Collection<DfsPackDescription> descriptions) {
    for (DfsPackDescription description : descriptions) {
      String packName = baseName(description);
      for (PackExt extension : PackExt.values()) {
        StagedExtensionKey key = new StagedExtensionKey(packName, extension.getExtension());
        StagedPackExtension staged = stagedExtensions.remove(key);
        if (staged != null) {
          staged.deleteBestEffort();
        }
      }
    }
  }

  @Override
  public Set<ObjectId> getShallowCommits() throws IOException {
    return Set.of();
  }

  @Override
  public void setShallowCommits(Set<ObjectId> shallowCommits) {
    if (shallowCommits != null && !shallowCommits.isEmpty()) {
      throw new UnsupportedOperationException(
          "Shallow repositories are not supported by jgit-storage-hibernate");
    }
  }

  @Override
  public long getApproximateObjectCount() {
    // Pack storage does not maintain a reliable object-level count.
    return 0L;
  }

  private record StagedExtensionKey(String packName, String packExtension) {}

  @FunctionalInterface
  interface ExtensionStager {
    void stage(StagedPackExtension staged) throws IOException;
  }

  static final class StagedPackExtension {
    private final String packName;
    private final String packExtension;
    private final Path temporaryFile;
    private final long fileSize;
    private final Instant createdAt;

    StagedPackExtension(
        String packName,
        String packExtension,
        Path temporaryFile,
        long fileSize,
        Instant createdAt) {
      this.packName = packName;
      this.packExtension = packExtension;
      this.temporaryFile = temporaryFile;
      this.fileSize = fileSize;
      this.createdAt = createdAt;
    }

    String packName() {
      return packName;
    }

    String packExtension() {
      return packExtension;
    }

    long fileSize() {
      return fileSize;
    }

    Instant createdAt() {
      return createdAt;
    }

    byte[] readInlinePayload() throws IOException {
      return Files.readAllBytes(temporaryFile);
    }

    void persistChunks(Session session, Long packId) throws IOException {
      try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.READ)) {
        byte[] chunkBuffer = new byte[PACK_CHUNK_SIZE];
        long position = 0;
        int chunkIndex = 0;
        int pendingChunks = 0;
        while (position < fileSize) {
          int chunkLength = (int) Math.min(PACK_CHUNK_SIZE, fileSize - position);
          ByteBuffer destination = ByteBuffer.wrap(chunkBuffer, 0, chunkLength);
          long chunkPosition = position;
          while (destination.hasRemaining()) {
            int count = channel.read(destination, chunkPosition);
            if (count <= 0) {
              throw new IOException(
                  "Temporary pack file ended before declared size " + fileSize);
            }
            chunkPosition += count;
          }

          GitPackChunkEntity chunk = new GitPackChunkEntity();
          chunk.setPackId(packId);
          chunk.setChunkIndex(chunkIndex);
          chunk.setChunkSize(chunkLength);
          chunk.setData(
              chunkLength == PACK_CHUNK_SIZE
                  ? chunkBuffer.clone()
                  : Arrays.copyOf(chunkBuffer, chunkLength));
          session.persist(chunk);

          position += chunkLength;
          chunkIndex++;
          pendingChunks++;
          if (pendingChunks == CHUNK_BATCH_SIZE) {
            session.flush();
            session.clear();
            pendingChunks = 0;
          }
        }
        if (pendingChunks > 0) {
          session.flush();
        }
      }
    }

    void deleteBestEffort() {
      try {
        Files.deleteIfExists(temporaryFile);
      } catch (IOException failure) {
        LOGGER.log(
            System.Logger.Level.WARNING,
            "Could not delete staged pack extension " + temporaryFile,
            failure);
      }
    }
  }

  static final class HibernatePackOutputStream extends DfsOutputStream {
    private final String packName;
    private final String packExtension;
    private final ExtensionStager stager;
    private final Path temporaryFile;
    private final FileChannel fileChannel;
    private final Instant createdAt = Instant.now();
    private long fileSize;
    private boolean closed;

    HibernatePackOutputStream(
        String packName, String packExtension, ExtensionStager stager) throws IOException {
      this.packName = packName;
      this.packExtension = packExtension;
      this.stager = stager;
      temporaryFile = Files.createTempFile("jgit-storage-pack-", ".tmp");
      temporaryFile.toFile().deleteOnExit();
      fileChannel =
          FileChannel.open(
              temporaryFile,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
      ensureOpen();
      if (offset < 0 || length < 0 || offset > source.length - length) {
        throw new IndexOutOfBoundsException();
      }
      ByteBuffer buffer = ByteBuffer.wrap(source, offset, length);
      long writePosition = fileSize;
      while (buffer.hasRemaining()) {
        int count = fileChannel.write(buffer, writePosition);
        if (count <= 0) {
          throw new IOException("Could not append to temporary pack file");
        }
        writePosition += count;
      }
      fileSize = writePosition;
    }

    @Override
    public int read(long position, ByteBuffer destination) throws IOException {
      ensureOpen();
      if (position < 0) {
        throw new IllegalArgumentException("position must not be negative");
      }
      if (position >= fileSize) {
        return -1;
      }
      int total = 0;
      long readPosition = position;
      while (destination.hasRemaining() && readPosition < fileSize) {
        int originalLimit = destination.limit();
        int allowed = (int) Math.min(destination.remaining(), fileSize - readPosition);
        destination.limit(destination.position() + allowed);
        int count;
        try {
          count = fileChannel.read(destination, readPosition);
        } finally {
          destination.limit(originalLimit);
        }
        if (count <= 0) {
          break;
        }
        total += count;
        readPosition += count;
      }
      return total == 0 ? -1 : total;
    }

    @Override
    public void flush() throws IOException {
      ensureOpen();
      fileChannel.force(false);
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      IOException failure = null;
      try {
        fileChannel.close();
      } catch (IOException exception) {
        failure = exception;
      }
      if (failure == null) {
        try {
          stager.stage(
              new StagedPackExtension(
                  packName, packExtension, temporaryFile, fileSize, createdAt));
          return;
        } catch (IOException exception) {
          failure = exception;
        }
      }
      try {
        Files.deleteIfExists(temporaryFile);
      } catch (IOException exception) {
        failure.addSuppressed(exception);
      }
      throw failure;
    }

    private void ensureOpen() throws IOException {
      if (closed) {
        throw new IOException("Pack output stream is closed");
      }
    }
  }

  static final class ChunkedReadableChannel implements ReadableChannel {
    private final SessionFactory sessionFactory;
    private final Long packId;
    private final long fileSize;
    private long position;
    private boolean open = true;
    private int cachedChunkIndex = -1;
    private byte[] cachedChunk;

    ChunkedReadableChannel(SessionFactory sessionFactory, Long packId, long fileSize) {
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
        long chunkNumber = position / PACK_CHUNK_SIZE;
        int chunkIndex = Math.toIntExact(chunkNumber);
        int offset = (int) (position % PACK_CHUNK_SIZE);
        byte[] chunk = loadChunk(chunkIndex);
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

    private byte[] loadChunk(int chunkIndex) throws IOException {
      if (cachedChunkIndex == chunkIndex && cachedChunk != null) {
        return cachedChunk;
      }
      try (Session session = sessionFactory.openSession()) {
        Object[] row =
            session
                .createQuery(
                    "SELECT c.data, c.chunkSize FROM GitPackChunkEntity c "
                        + "WHERE c.packId = :packId AND c.chunkIndex = :chunkIndex",
                    Object[].class)
                .setParameter("packId", packId)
                .setParameter("chunkIndex", chunkIndex)
                .uniqueResult();
        if (row == null) {
          throw new IOException("Missing chunk " + chunkIndex + " for pack " + packId);
        }
        byte[] data = (byte[]) row[0];
        int declaredSize = ((Number) row[1]).intValue();
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
        cachedChunkIndex = chunkIndex;
        cachedChunk = data;
        return data;
      }
    }

    @Override
    public void close() {
      open = false;
      cachedChunk = null;
      cachedChunkIndex = -1;
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
    public void setReadAheadBytes(int readAheadBytes) {
      // Base implementation loads one chunk at a time.
    }

    private void ensureOpen() throws IOException {
      if (!open) {
        throw new IOException("Pack channel is closed");
      }
    }
  }

  private static final class ByteArrayReadableChannel implements ReadableChannel {
    private final byte[] data;
    private int position;
    private boolean open = true;

    private ByteArrayReadableChannel(byte[] data) {
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
      // Inline payload is already fully loaded.
    }

    private void ensureOpen() throws IOException {
      if (!open) {
        throw new IOException("Pack channel is closed");
      }
    }
  }
}
