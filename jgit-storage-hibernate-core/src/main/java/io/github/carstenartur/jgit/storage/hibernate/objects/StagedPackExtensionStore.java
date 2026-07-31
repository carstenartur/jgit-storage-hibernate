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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * {@code commitPack}. Until that publication callback, the bytes remain in bounded temporary files
 * and no database row is created. Publication persists all expected extensions and marks them
 * committed in the same repository-locked Hibernate transaction.
 *
 * <p>The temporary files are derived, unpublished state. Normal publication and rollback delete them
 * explicitly without registering every file in the JVM-wide {@code deleteOnExit} registry. A process
 * crash or operating-system deletion failure may leave files with the {@code jgit-storage-pack-}
 * prefix in the configured temporary directory; unlike the former uncommitted database rows, those
 * files are never interpreted as durable or resumable writes.
 */
final class StagedPackExtensionStore {

  private static final int CHUNK_BATCH_SIZE = 8;
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
    CommitResult commitResult =
        transactionContext.executeWithRepositoryLock(
            repositoryName,
            session -> {
              deletePackRows(session, packNames(replaces));

              List<CommittedExtension> committed = new ArrayList<>();
              boolean completeMetadata = true;
              Instant committedAt = Instant.now();
              for (Publication publication : publications) {
                PackDescriptionMetadata metadata =
                    PackDescriptionMetadata.fromDescription(publication.description(), committedAt);
                for (ExpectedExtension expected : publication.extensions()) {
                  if (expected.staged() != null) {
                    committed.add(
                        persistCommitted(session, expected.staged(), committedAt, metadata));
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
            });

    for (Publication publication : publications) {
      for (ExpectedExtension expected : publication.extensions()) {
        StagedExtension extension = expected.staged();
        if (extension != null) {
          staged.remove(extension.key(), extension);
          extension.discard();
        }
      }
    }
    return commitResult;
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
        inlineData);
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
      byte[] inlineData) {
    CommittedExtension {
      if (inline != (inlineData != null)) {
        throw new IllegalArgumentException("inline payload must be present exactly for inline rows");
      }
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
