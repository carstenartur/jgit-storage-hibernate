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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;

/**
 * JVM-local staging for unpublished DFS pack extensions.
 *
 * <p>JGit may read data back through an open {@link DfsOutputStream} while it parses or builds an
 * extension. Once the stream is closed, JGit completes the remaining extensions and calls
 * {@code commitPack}. Until that callback, the bytes remain in bounded temporary files and no
 * database row is visible.
 *
 * <p>At publication time, extensions that no longer fit the inline payload column are persisted as
 * leased {@code committed=false} rows before the repository lock is acquired. The short locked
 * transaction then deletes replaced packs, validates ownership of the prepared rows, writes any
 * remaining inline extensions and atomically switches the complete generation to
 * {@code committed=true}. Readers therefore retain the same all-or-nothing visibility contract while
 * large payload transfer no longer extends repository-scoped lock hold time.
 *
 * <p>The temporary files and leased database rows are derived, unpublished state. Normal
 * publication and rollback remove them explicitly. A process crash may leave files with the
 * {@code jgit-storage-pack-} prefix and leased uncommitted rows; the existing maintenance API may
 * reclaim both after operators have verified that no matching writer is active.
 */
final class StagedPackExtensionStore {

  private static final int CHUNK_BATCH_SIZE = 8;
  private static final long PREPERSIST_THRESHOLD_BYTES =
      HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 1L;
  private static final String PACK_EXTENSION_UNIQUE_CONSTRAINT = "uk_pack_repo_name_ext";

  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;
  private final Map<ExtensionKey, StagedExtension> staged = new ConcurrentHashMap<>();

  StagedPackExtensionStore(
      String repositoryName, HibernateTransactionContext transactionContext) {
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
    this.transactionContext = Objects.requireNonNull(transactionContext, "transactionContext");
  }

  DfsOutputStream open(DfsPackDescription description, PackExt extension) throws IOException {
    String packName = baseName(description);
    ExtensionKey key = new ExtensionKey(packName, extension.getExtension());
    if (staged.containsKey(key)) {
      throw new IOException("Pack extension is already staged: " + key.displayName());
    }
    return new StagedOutputStream(key, this::register);
  }

  CommitResult commit(
      Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replaces)
      throws IOException {
    List<Publication> publications = publications(descriptions);
    PrePersistence prePersistence = prePersistChunkedExtensions(publications);
    try {
      CommitResult commitResult =
          transactionContext.executeWithRepositoryLock(
              StorageOperationKind.PACK_PUBLICATION,
              repositoryName,
              session -> publish(session, publications, replaces, prePersistence));
      discardStagedExtensions(publications);
      return commitResult;
    } catch (IOException | RuntimeException publicationFailure) {
      cleanupPrePersisted(prePersistence, publicationFailure);
      throw publicationFailure;
    }
  }

  private CommitResult publish(
      Session session,
      List<Publication> publications,
      Collection<DfsPackDescription> replaces,
      PrePersistence prePersistence)
      throws IOException {
    deletePackRows(session, packNames(replaces));

    List<CommittedExtension> committed = new ArrayList<>();
    boolean completeMetadata = true;
    Instant committedAt = Instant.now();
    for (Publication publication : publications) {
      PackDescriptionMetadata metadata =
          PackDescriptionMetadata.fromDescription(publication.description(), committedAt);
      for (ExpectedExtension expected : publication.extensions()) {
        ExtensionKey key = new ExtensionKey(publication.packName(), expected.extension());
        PrePersistedExtension prepared = prePersistence.extensions().get(key);
        if (prepared != null) {
          committed.add(publishPrePersisted(session, prepared, committedAt, metadata));
        } else if (expected.staged() != null) {
          committed.add(persistCommitted(session, expected.staged(), committedAt, metadata));
        } else {
          publishLegacyExtension(
              session,
              publication.packName(),
              expected.extension(),
              committedAt,
              metadata);
          completeMetadata = false;
        }
      }
    }
    return new CommitResult(List.copyOf(committed), completeMetadata);
  }

  private PrePersistence prePersistChunkedExtensions(List<Publication> publications)
      throws IOException {
    List<PrePersistCandidate> candidates = new ArrayList<>();
    Instant preparedAt = Instant.now();
    for (Publication publication : publications) {
      PackDescriptionMetadata metadata =
          PackDescriptionMetadata.fromDescription(publication.description(), preparedAt);
      for (ExpectedExtension expected : publication.extensions()) {
        if (expected.staged() != null
            && expected.staged().fileSize() >= PREPERSIST_THRESHOLD_BYTES) {
          candidates.add(
              new PrePersistCandidate(publication.packName(), expected.staged(), metadata));
        }
      }
    }
    if (candidates.isEmpty()) {
      return PrePersistence.empty();
    }

    String writeToken = UUID.randomUUID().toString();
    List<PrePersistedExtension> prepared =
        transactionContext.execute(
            StorageOperationKind.PACK_EXTENSION_WRITE,
            session -> {
              List<PrePersistedExtension> persisted = new ArrayList<>();
              for (PrePersistCandidate candidate : candidates) {
                persisted.add(
                    persistUncommitted(
                        session,
                        candidate.packName(),
                        candidate.staged(),
                        writeToken,
                        candidate.metadata()));
              }

              Instant leaseUntil = Instant.now().plus(HibernateObjDatabase.PACK_WRITE_LEASE);
              int leased =
                  session
                      .createMutationQuery(
                          "UPDATE GitPackEntity p SET p.writeLeaseUntil = :leaseUntil "
                              + "WHERE p.repositoryName = :repo AND p.writeToken = :token "
                              + "AND p.committed = false")
                      .setParameter("leaseUntil", leaseUntil)
                      .setParameter("repo", repositoryName)
                      .setParameter("token", writeToken)
                      .executeUpdate();
              if (leased != persisted.size()) {
                throw new IOException(
                    "Could not lease every pre-persisted pack extension for " + repositoryName);
              }
              return List.copyOf(persisted);
            });

    LinkedHashMap<ExtensionKey, PrePersistedExtension> byExtension = new LinkedHashMap<>();
    for (PrePersistedExtension extension : prepared) {
      byExtension.put(extension.key(), extension);
    }
    return new PrePersistence(writeToken, Map.copyOf(byExtension));
  }

  private PrePersistedExtension persistUncommitted(
      Session session,
      String packName,
      StagedExtension stagedExtension,
      String writeToken,
      PackDescriptionMetadata metadata)
      throws IOException {
    GitPackEntity entity = new GitPackEntity();
    entity.setRepositoryName(repositoryName);
    entity.setPackName(packName);
    entity.setPackExtension(stagedExtension.key().extension());
    entity.setFileSize(stagedExtension.fileSize());
    entity.setCommitted(false);
    entity.setCreatedAt(stagedExtension.createdAt());
    entity.setCommittedAt(null);
    entity.setWriteToken(writeToken);
    entity.setWriteLeaseUntil(null);
    entity.setData(null);
    metadata.applyTo(entity);

    try (FileChannel channel =
        FileChannel.open(stagedExtension.temporaryFile(), StandardOpenOption.READ)) {
      try {
        session.persist(entity);
        session.flush();
        persistChunks(session, entity.getId(), channel, stagedExtension.fileSize());
      } catch (RuntimeException exception) {
        if (isDuplicatePackExtension(exception)) {
          throw new IOException(
              "Pack extension already exists: " + stagedExtension.key().displayName(), exception);
        }
        throw exception;
      }
    }
    return new PrePersistedExtension(
        stagedExtension.key(), entity.getId(), stagedExtension.fileSize(), writeToken);
  }

  private CommittedExtension publishPrePersisted(
      Session session,
      PrePersistedExtension prepared,
      Instant committedAt,
      PackDescriptionMetadata metadata)
      throws IOException {
    int updated =
        session
            .createMutationQuery(
                "UPDATE GitPackEntity p SET p.committed = true, "
                    + "p.committedAt = :committedAt, p.writeToken = null, "
                    + "p.writeLeaseUntil = null, p.packSource = :packSource, "
                    + "p.lastModified = :lastModified, p.objectCount = :objectCount, "
                    + "p.deltaCount = :deltaCount, p.indexVersion = :indexVersion, "
                    + "p.minUpdateIndex = :minUpdateIndex, p.maxUpdateIndex = :maxUpdateIndex "
                    + "WHERE p.id = :id AND p.repositoryName = :repo "
                    + "AND p.packName = :name AND p.packExtension = :ext "
                    + "AND p.fileSize = :fileSize AND p.data IS NULL "
                    + "AND p.committed = false AND p.writeToken = :token "
                    + "AND p.writeLeaseUntil IS NOT NULL "
                    + "AND p.writeLeaseUntil >= :committedAt")
            .setParameter("committedAt", committedAt)
            .setParameter("packSource", metadata.packSource().name())
            .setParameter("lastModified", metadata.lastModified())
            .setParameter("objectCount", metadata.objectCount())
            .setParameter("deltaCount", metadata.deltaCount())
            .setParameter("indexVersion", metadata.indexVersion())
            .setParameter("minUpdateIndex", metadata.minUpdateIndex())
            .setParameter("maxUpdateIndex", metadata.maxUpdateIndex())
            .setParameter("id", prepared.packId())
            .setParameter("repo", repositoryName)
            .setParameter("name", prepared.key().packName())
            .setParameter("ext", prepared.key().extension())
            .setParameter("fileSize", prepared.fileSize())
            .setParameter("token", prepared.writeToken())
            .executeUpdate();
    if (updated != 1) {
      throw new IOException(
          "Cannot publish missing, expired or foreign pre-persisted pack extension "
              + prepared.key().displayName());
    }
    return new CommittedExtension(
        prepared.key().packName(),
        prepared.key().extension(),
        prepared.packId(),
        prepared.fileSize(),
        false,
        null,
        metadata);
  }

  private void cleanupPrePersisted(PrePersistence prePersistence, Throwable originalFailure) {
    if (prePersistence.extensions().isEmpty()) {
      return;
    }
    try {
      transactionContext.execute(
          StorageOperationKind.PACK_ROLLBACK,
          session -> {
            session
                .createMutationQuery(
                    "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                        + "AND p.writeToken = :token AND p.committed = false")
                .setParameter("repo", repositoryName)
                .setParameter("token", prePersistence.writeToken())
                .executeUpdate();
            return null;
          });
    } catch (IOException | RuntimeException cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
    }
  }

  private void discardStagedExtensions(List<Publication> publications) {
    for (Publication publication : publications) {
      for (ExpectedExtension expected : publication.extensions()) {
        StagedExtension extension = expected.staged();
        if (extension != null) {
          staged.remove(extension.key(), extension);
          extension.discard();
        }
      }
    }
  }

  void rollback(Collection<DfsPackDescription> descriptions) {
    List<String> databasePackNames = new ArrayList<>();
    for (DfsPackDescription description : descriptions) {
      String packName = baseName(description);
      int removedExtensions = 0;
      boolean hasUnstagedExpectedExtension = false;
      for (PackExt extension : PackExt.values()) {
        if (!description.hasFileExt(extension)) {
          continue;
        }
        ExtensionKey key = new ExtensionKey(packName, extension.getExtension());
        StagedExtension removed = staged.remove(key);
        if (removed == null) {
          hasUnstagedExpectedExtension = true;
        } else {
          removedExtensions++;
          removed.discard();
        }
      }

      for (Map.Entry<ExtensionKey, StagedExtension> entry : List.copyOf(staged.entrySet())) {
        if (entry.getKey().packName().equals(packName)
            && staged.remove(entry.getKey(), entry.getValue())) {
          removedExtensions++;
          entry.getValue().discard();
        }
      }

      if (hasUnstagedExpectedExtension || removedExtensions == 0) {
        databasePackNames.add(packName);
      }
    }

    if (databasePackNames.isEmpty()) {
      return;
    }

    try {
      transactionContext.executeWithRepositoryLock(
          StorageOperationKind.PACK_ROLLBACK,
          repositoryName,
          session -> {
            deletePackRows(session, databasePackNames);
            return null;
          });
    } catch (IOException | RuntimeException ignored) {
      // Rollback is best-effort and must not mask the original JGit exception.
    }
  }

  int stagedExtensionCount() {
    return staged.size();
  }

  private void register(StagedExtension extension) throws IOException {
    StagedExtension previous = staged.putIfAbsent(extension.key(), extension);
    if (previous != null) {
      throw new IOException("Pack extension is already staged: " + extension.key().displayName());
    }
  }

  private List<Publication> publications(Collection<DfsPackDescription> descriptions)
      throws IOException {
    List<Publication> result = new ArrayList<>();
    for (DfsPackDescription description : descriptions) {
      String packName = baseName(description);
      List<ExpectedExtension> extensions = new ArrayList<>();
      for (PackExt extension : PackExt.values()) {
        if (description.hasFileExt(extension)) {
          ExtensionKey key = new ExtensionKey(packName, extension.getExtension());
          extensions.add(new ExpectedExtension(extension.getExtension(), staged.get(key)));
        }
      }
      if (extensions.isEmpty()) {
        throw new IOException("Cannot publish a pack without extensions: " + packName);
      }
      result.add(new Publication(packName, description, List.copyOf(extensions)));
    }
    return result;
  }

  private static Set<String> packNames(Collection<DfsPackDescription> descriptions) {
    if (descriptions == null || descriptions.isEmpty()) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (DfsPackDescription description : descriptions) {
      names.add(baseName(description));
    }
    return Set.copyOf(names);
  }

  private CommittedExtension persistCommitted(
      Session session,
      StagedExtension stagedExtension,
      Instant committedAt,
      PackDescriptionMetadata metadata)
      throws IOException {
    GitPackEntity entity = new GitPackEntity();
    entity.setRepositoryName(repositoryName);
    entity.setPackName(stagedExtension.key().packName());
    entity.setPackExtension(stagedExtension.key().extension());
    entity.setFileSize(stagedExtension.fileSize());
    entity.setCommitted(true);
    entity.setCreatedAt(stagedExtension.createdAt());
    entity.setCommittedAt(committedAt);
    entity.setWriteToken(null);
    entity.setWriteLeaseUntil(null);
    metadata.applyTo(entity);

    boolean inline = stagedExtension.fileSize() <= HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD;
    byte[] inlineData;
    try (FileChannel channel =
        FileChannel.open(stagedExtension.temporaryFile(), StandardOpenOption.READ)) {
      inlineData = inline ? readInline(channel, stagedExtension.fileSize()) : null;
      entity.setData(inlineData);
      try {
        session.persist(entity);
        session.flush();
        if (!inline) {
          persistChunks(session, entity.getId(), channel, stagedExtension.fileSize());
        }
      } catch (RuntimeException exception) {
        if (isDuplicatePackExtension(exception)) {
          throw new IOException(
              "Pack extension already exists: " + stagedExtension.key().displayName(), exception);
        }
        throw exception;
      }
    }
    return new CommittedExtension(
        stagedExtension.key().packName(),
        stagedExtension.key().extension(),
        entity.getId(),
        stagedExtension.fileSize(),
        inline,
        inlineData,
        metadata);
  }

  private static boolean isDuplicatePackExtension(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ConstraintViolationException constraintViolation) {
        String constraintName = constraintViolation.getConstraintName();
        if (constraintName != null
            && constraintName
                .toLowerCase(Locale.ROOT)
                .contains(PACK_EXTENSION_UNIQUE_CONSTRAINT)) {
          return true;
        }
        if (isDuplicateSqlException(constraintViolation.getSQLException())) {
          return true;
        }
      }
      if (current instanceof SQLException sqlException && isDuplicateSqlException(sqlException)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDuplicateSqlException(SQLException exception) {
    for (SQLException current = exception; current != null; current = current.getNextException()) {
      String sqlState = current.getSQLState();
      if ("23505".equals(sqlState)
          || current.getErrorCode() == 2601
          || current.getErrorCode() == 2627) {
        return true;
      }
      String message = current.getMessage();
      if (message != null
          && message.toLowerCase(Locale.ROOT).contains(PACK_EXTENSION_UNIQUE_CONSTRAINT)) {
        return true;
      }
    }
    return false;
  }

  private void publishLegacyExtension(
      Session session,
      String packName,
      String extension,
      Instant committedAt,
      PackDescriptionMetadata metadata)
      throws IOException {
    int updated =
        session
            .createMutationQuery(
                "UPDATE GitPackEntity p SET p.committed = true, "
                    + "p.committedAt = :committedAt, p.writeToken = null, "
                    + "p.writeLeaseUntil = null, p.packSource = :packSource, "
                    + "p.lastModified = :lastModified, p.objectCount = :objectCount, "
                    + "p.deltaCount = :deltaCount, p.indexVersion = :indexVersion, "
                    + "p.minUpdateIndex = :minUpdateIndex, p.maxUpdateIndex = :maxUpdateIndex "
                    + "WHERE p.repositoryName = :repo AND p.packName = :name "
                    + "AND p.packExtension = :ext AND p.committed = false")
            .setParameter("committedAt", committedAt)
            .setParameter("packSource", metadata.packSource().name())
            .setParameter("lastModified", metadata.lastModified())
            .setParameter("objectCount", metadata.objectCount())
            .setParameter("deltaCount", metadata.deltaCount())
            .setParameter("indexVersion", metadata.indexVersion())
            .setParameter("minUpdateIndex", metadata.minUpdateIndex())
            .setParameter("maxUpdateIndex", metadata.maxUpdateIndex())
            .setParameter("repo", repositoryName)
            .setParameter("name", packName)
            .setParameter("ext", extension)
            .executeUpdate();
    if (updated != 1) {
      throw new IOException(
          "Cannot publish missing or already committed pack extension "
              + packName
              + "."
              + extension);
    }
  }

  private static byte[] readInline(FileChannel channel, long fileSize) throws IOException {
    byte[] data = new byte[Math.toIntExact(fileSize)];
    ByteBuffer destination = ByteBuffer.wrap(data);
    long position = 0;
    while (destination.hasRemaining()) {
      int count = channel.read(destination, position);
      if (count <= 0) {
        throw new IOException("Temporary pack file ended before declared size " + fileSize);
      }
      position += count;
    }
    return data;
  }

  private static void persistChunks(
      Session session, Long packId, FileChannel channel, long fileSize) throws IOException {
    byte[] chunkBuffer = new byte[HibernateObjDatabase.PACK_CHUNK_SIZE];
    long position = 0;
    int chunkIndex = 0;
    int pendingChunks = 0;
    while (position < fileSize) {
      int chunkLength =
          (int) Math.min(HibernateObjDatabase.PACK_CHUNK_SIZE, fileSize - position);
      ByteBuffer destination = ByteBuffer.wrap(chunkBuffer, 0, chunkLength);
      long chunkPosition = position;
      while (destination.hasRemaining()) {
        int count = channel.read(destination, chunkPosition);
        if (count <= 0) {
          throw new IOException("Temporary pack file ended before declared size " + fileSize);
        }
        chunkPosition += count;
      }

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(packId);
      chunk.setChunkIndex(chunkIndex);
      chunk.setChunkSize(chunkLength);
      chunk.setData(
          chunkLength == HibernateObjDatabase.PACK_CHUNK_SIZE
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

  private void deletePackRows(Session session, Collection<String> packNames) {
    if (packNames.isEmpty()) {
      return;
    }
    session
        .createMutationQuery(
            "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                + "AND p.packName IN :packNames")
        .setParameter("repo", repositoryName)
        .setParameter("packNames", packNames)
        .executeUpdate();
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  record CommitResult(List<CommittedExtension> committedExtensions, boolean completeMetadata) {
    CommitResult {
      committedExtensions = List.copyOf(committedExtensions);
    }
  }

  record CommittedExtension(
      String packName,
      String extension,
      Long packId,
      long fileSize,
      boolean inline,
      byte[] inlineData,
      PackDescriptionMetadata metadata) {
    CommittedExtension {
      if (inline != (inlineData != null)) {
        throw new IllegalArgumentException("inline payload must be present exactly for inline rows");
      }
      metadata = Objects.requireNonNull(metadata, "metadata");
    }
  }

  private record ExtensionKey(String packName, String extension) {
    private String displayName() {
      return packName + "." + extension;
    }
  }

  private record ExpectedExtension(String extension, StagedExtension staged) {}

  private record Publication(
      String packName, DfsPackDescription description, List<ExpectedExtension> extensions) {}

  private record PrePersistCandidate(
      String packName, StagedExtension staged, PackDescriptionMetadata metadata) {}

  private record PrePersistedExtension(
      ExtensionKey key, Long packId, long fileSize, String writeToken) {}

  private record PrePersistence(
      String writeToken, Map<ExtensionKey, PrePersistedExtension> extensions) {
    private PrePersistence {
      extensions = Map.copyOf(extensions);
    }

    private static PrePersistence empty() {
      return new PrePersistence(null, Map.of());
    }
  }

  @FunctionalInterface
  private interface StagingConsumer {
    void accept(StagedExtension extension) throws IOException;
  }

  private static final class StagedExtension {
    private final ExtensionKey key;
    private final Path temporaryFile;
    private final long fileSize;
    private final Instant createdAt;

    private StagedExtension(
        ExtensionKey key, Path temporaryFile, long fileSize, Instant createdAt) {
      this.key = key;
      this.temporaryFile = temporaryFile;
      this.fileSize = fileSize;
      this.createdAt = createdAt;
    }

    private ExtensionKey key() {
      return key;
    }

    private Path temporaryFile() {
      return temporaryFile;
    }

    private long fileSize() {
      return fileSize;
    }

    private Instant createdAt() {
      return createdAt;
    }

    private void discard() {
      try {
        Files.deleteIfExists(temporaryFile);
      } catch (IOException ignored) {
        // The file is unpublished derived state. Operators may remove stale prefixed files from the
        // configured temporary directory after verifying no matching process is active.
      }
    }
  }

  private static final class StagedOutputStream extends DfsOutputStream {
    private final ExtensionKey key;
    private final StagingConsumer stagingConsumer;
    private final Path temporaryFile;
    private final FileChannel fileChannel;
    private final Instant createdAt = Instant.now();
    private long fileSize;
    private boolean closed;

    private StagedOutputStream(ExtensionKey key, StagingConsumer stagingConsumer)
        throws IOException {
      this.key = key;
      this.stagingConsumer = stagingConsumer;
      temporaryFile = Files.createTempFile("jgit-storage-pack-", ".tmp");
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
      // FileChannel writes are immediately visible to this stream's random-read path. Durability and
      // database visibility are intentionally deferred to commitPack().
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
          stagingConsumer.accept(new StagedExtension(key, temporaryFile, fileSize, createdAt));
          return;
        } catch (IOException exception) {
          failure = exception;
        }
      }
      try {
        Files.deleteIfExists(temporaryFile);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }

    private void ensureOpen() throws IOException {
      if (closed) {
        throw new IOException("Pack output stream is closed");
      }
    }
  }
}
