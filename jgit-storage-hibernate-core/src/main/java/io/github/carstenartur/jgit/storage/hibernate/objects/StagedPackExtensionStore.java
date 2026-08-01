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
import java.util.Collection;
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
 * extension. Closing the stream retains the completed bytes in a bounded temporary file and creates
 * no database row.
 *
 * <p>Additive logical packs whose extensions all remain inline use one repository-locked transaction
 * for persistence and publication. Small chunked logical packs also retain that direct path: below
 * one MiB of total staged payload, the second transaction costs more than the lock interval it can
 * save under ordinary low-contention operation. Larger additive packs are persisted as a complete
 * invisible lease-owned generation in one lock-free Hibernate transaction, then atomically
 * published under one short repository lock. Readers continue to select only
 * {@code committed=true} rows.
 *
 * <p>Pack replacement and compaction remain on the established single locked transaction path. JGit
 * can validate refs before calling {@code commitPack}; releasing the repository lock between that
 * validation and source-pack replacement would permit a conflicting ref update to cross the race
 * check.
 *
 * <p>A crash after pre-persistence can leave a complete durable uncommitted group. Its shared writer
 * token and renewed lease make it eligible for the existing abandoned-write cleanup after expiry.
 * Normal publication, failure handling and rollback remove it explicitly. Local temporary files
 * remain derived unpublished state and are never imported as durable Git data.
 */
final class StagedPackExtensionStore {

  private static final int CHUNK_BATCH_SIZE = 8;
  static final long DEFAULT_PREPERSIST_MIN_PAYLOAD_BYTES = 1024L * 1024L;
  private static final long MIN_PREPERSIST_PAYLOAD_BYTES =
      HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 1L;
  private static final String PACK_EXTENSION_UNIQUE_CONSTRAINT = "uk_pack_repo_name_ext";
  private static final PrePublicationHook NO_PRE_PUBLICATION_HOOK = ignored -> {};

  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;
  private final PrePublicationHook prePublicationHook;
  private final long minimumPrePersistedPayloadBytes;
  private final StorageByteCounters storageByteCounters;
  private final Map<ExtensionKey, StagedExtension> staged = new ConcurrentHashMap<>();
  private final Map<String, String> preparedTokensByPackName = new ConcurrentHashMap<>();

  StagedPackExtensionStore(
      String repositoryName, HibernateTransactionContext transactionContext) {
    this(
        repositoryName,
        transactionContext,
        NO_PRE_PUBLICATION_HOOK,
        DEFAULT_PREPERSIST_MIN_PAYLOAD_BYTES,
        StorageByteCounters.disabled());
  }

  StagedPackExtensionStore(
      String repositoryName,
      HibernateTransactionContext transactionContext,
      StorageByteCounters storageByteCounters) {
    this(
        repositoryName,
        transactionContext,
        NO_PRE_PUBLICATION_HOOK,
        DEFAULT_PREPERSIST_MIN_PAYLOAD_BYTES,
        storageByteCounters);
  }

  StagedPackExtensionStore(
      String repositoryName,
      HibernateTransactionContext transactionContext,
      PrePublicationHook prePublicationHook) {
    this(
        repositoryName,
        transactionContext,
        prePublicationHook,
        MIN_PREPERSIST_PAYLOAD_BYTES,
        StorageByteCounters.disabled());
  }

  StagedPackExtensionStore(
      String repositoryName,
      HibernateTransactionContext transactionContext,
      PrePublicationHook prePublicationHook,
      long minimumPrePersistedPayloadBytes) {
    this(
        repositoryName,
        transactionContext,
        prePublicationHook,
        minimumPrePersistedPayloadBytes,
        StorageByteCounters.disabled());
  }

  StagedPackExtensionStore(
      String repositoryName,
      HibernateTransactionContext transactionContext,
      PrePublicationHook prePublicationHook,
      long minimumPrePersistedPayloadBytes,
      StorageByteCounters storageByteCounters) {
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
    this.transactionContext = Objects.requireNonNull(transactionContext, "transactionContext");
    this.prePublicationHook = Objects.requireNonNull(prePublicationHook, "prePublicationHook");
    if (minimumPrePersistedPayloadBytes < MIN_PREPERSIST_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "minimumPrePersistedPayloadBytes must be at least " + MIN_PREPERSIST_PAYLOAD_BYTES);
    }
    this.minimumPrePersistedPayloadBytes = minimumPrePersistedPayloadBytes;
    this.storageByteCounters = Objects.requireNonNull(storageByteCounters, "storageByteCounters");
  }

  DfsOutputStream open(DfsPackDescription description, PackExt extension) throws IOException {
    String packName = baseName(description);
    ExtensionKey key = new ExtensionKey(packName, extension.getExtension());
    if (staged.containsKey(key)) {
      throw new IOException("Pack extension is already staged: " + key.displayName());
    }
    return new StagedOutputStream(key, this::register, storageByteCounters);
  }

  CommitResult commit(
      Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replaces)
      throws IOException {
    List<Publication> publications = publications(descriptions);
    CommitResult commitResult =
        shouldPrePersist(publications, replaces)
            ? commitPrePersisted(publications)
            : commitDirect(publications, replaces);
    discard(publications);
    return commitResult;
  }

  private CommitResult commitDirect(
      List<Publication> publications, Collection<DfsPackDescription> replaces)
      throws IOException {
    CommitResult result =
        transactionContext.executeWithRepositoryLock(
            StorageOperationKind.PACK_PUBLICATION,
            repositoryName,
            session -> {
              deletePackRows(session, packNames(replaces));

              List<CommittedExtension> committed = new ArrayList<>();
              boolean completeMetadata = true;
              Instant committedAt = Instant.now();
              for (Publication publication : publications) {
                PackDescriptionMetadata metadata =
                    PackDescriptionMetadata.fromDescription(
                        publication.description(), committedAt);
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
    storageByteCounters.recordDatabasePayloadBytesWritten(
        committedPayloadBytes(result.committedExtensions()));
    return result;
  }

  private CommitResult commitPrePersisted(List<Publication> publications) throws IOException {
    Instant preparedAt = Instant.now();
    String writeToken = UUID.randomUUID().toString();
    Set<String> preparedPackNames = publicationPackNames(publications);
    List<PersistedExtension> persisted =
        persistPreparedPayloads(publications, writeToken, preparedAt);
    storageByteCounters.recordDatabasePayloadBytesWritten(persistedPayloadBytes(persisted));
    for (String packName : preparedPackNames) {
      preparedTokensByPackName.put(packName, writeToken);
    }

    try {
      prePublicationHook.afterPayloadPersisted(writeToken);
      CommitResult result = publishPrepared(persisted, writeToken, Instant.now());
      clearPreparedMappings(preparedPackNames, writeToken);
      return result;
    } catch (IOException | RuntimeException failure) {
      if (cleanupPrepared(writeToken, failure)) {
        clearPreparedMappings(preparedPackNames, writeToken);
      }
      throw failure;
    }
  }

  private List<PersistedExtension> persistPreparedPayloads(
      List<Publication> publications, String writeToken, Instant preparedAt) throws IOException {
    return transactionContext.execute(
        StorageOperationKind.PACK_EXTENSION_WRITE,
        session -> {
          List<PersistedExtension> persisted = new ArrayList<>();
          Instant initialLeaseUntil = preparedAt.plus(HibernateObjDatabase.PACK_WRITE_LEASE);
          for (Publication publication : publications) {
            PackDescriptionMetadata metadata =
                PackDescriptionMetadata.fromDescription(publication.description(), preparedAt);
            for (ExpectedExtension expected : publication.extensions()) {
              StagedExtension stagedExtension =
                  Objects.requireNonNull(
                      expected.staged(), "pre-persisted extensions must be locally staged");
              boolean inline =
                  stagedExtension.fileSize() <= HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD;
              byte[] inlineData;
              GitPackEntity entity = new GitPackEntity();
              entity.setRepositoryName(repositoryName);
              entity.setPackName(stagedExtension.key().packName());
              entity.setPackExtension(stagedExtension.key().extension());
              entity.setFileSize(stagedExtension.fileSize());
              entity.setCommitted(false);
              entity.setCreatedAt(stagedExtension.createdAt());
              entity.setCommittedAt(null);
              entity.setWriteToken(writeToken);
              entity.setWriteLeaseUntil(initialLeaseUntil);
              metadata.applyTo(entity);

              try (FileChannel channel =
                  FileChannel.open(
                      stagedExtension.temporaryFile(), StandardOpenOption.READ)) {
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
                        "Pack extension already exists: "
                            + stagedExtension.key().displayName(),
                        exception);
                  }
                  throw exception;
                }
              }
              persisted.add(
                  new PersistedExtension(
                      stagedExtension, entity.getId(), inline, inlineData, metadata));
            }
          }

          session.clear();
          Instant leaseUntil = Instant.now().plus(HibernateObjDatabase.PACK_WRITE_LEASE);
          int renewed =
              session
                  .createMutationQuery(
                      "UPDATE GitPackEntity p SET p.writeLeaseUntil = :leaseUntil "
                          + "WHERE p.repositoryName = :repo AND p.writeToken = :writeToken "
                          + "AND p.committed = false")
                  .setParameter("leaseUntil", leaseUntil)
                  .setParameter("repo", repositoryName)
                  .setParameter("writeToken", writeToken)
                  .executeUpdate();
          if (renewed != persisted.size()) {
            throw new IOException(
                "Prepared pack persistence expected "
                    + persisted.size()
                    + " extensions but renewed "
                    + renewed
                    + " for "
                    + repositoryName);
          }
          return List.copyOf(persisted);
        });
  }

  private CommitResult publishPrepared(
      List<PersistedExtension> persisted, String writeToken, Instant committedAt)
      throws IOException {
    return transactionContext.executeWithRepositoryLock(
        StorageOperationKind.PACK_PUBLICATION,
        repositoryName,
        session -> {
          int updated =
              session
                  .createMutationQuery(
                      "UPDATE GitPackEntity p SET p.committed = true, "
                          + "p.committedAt = :committedAt, p.writeToken = null, "
                          + "p.writeLeaseUntil = null WHERE p.repositoryName = :repo "
                          + "AND p.writeToken = :writeToken AND p.committed = false")
                  .setParameter("committedAt", committedAt)
                  .setParameter("repo", repositoryName)
                  .setParameter("writeToken", writeToken)
                  .executeUpdate();
          if (updated != persisted.size()) {
            throw new IOException(
                "Prepared pack publication expected "
                    + persisted.size()
                    + " extensions but updated "
                    + updated
                    + " for "
                    + repositoryName);
          }

          List<CommittedExtension> committed = new ArrayList<>(persisted.size());
          for (PersistedExtension extension : persisted) {
            StagedExtension stagedExtension = extension.staged();
            committed.add(
                new CommittedExtension(
                    stagedExtension.key().packName(),
                    stagedExtension.key().extension(),
                    extension.packId(),
                    stagedExtension.fileSize(),
                    extension.inline(),
                    extension.inlineData(),
                    extension.metadata()));
          }
          return new CommitResult(List.copyOf(committed), true);
        });
  }

  private boolean cleanupPrepared(String writeToken, Exception originalFailure) {
    try {
      transactionContext.execute(
          StorageOperationKind.PACK_ROLLBACK,
          session -> {
            deletePreparedRows(session, Set.of(writeToken));
            return null;
          });
      return true;
    } catch (IOException | RuntimeException cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
      return false;
    }
  }

  void rollback(Collection<DfsPackDescription> descriptions) {
    List<String> databasePackNames = new ArrayList<>();
    Set<String> preparedTokens = new LinkedHashSet<>();
    for (DfsPackDescription description : descriptions) {
      String packName = baseName(description);
      String preparedToken = preparedTokensByPackName.remove(packName);
      if (preparedToken != null) {
        preparedTokens.add(preparedToken);
      }

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

      if (preparedToken == null && (hasUnstagedExpectedExtension || removedExtensions == 0)) {
        databasePackNames.add(packName);
      }
    }

    if (!preparedTokens.isEmpty()) {
      try {
        transactionContext.execute(
            StorageOperationKind.PACK_ROLLBACK,
            session -> {
              deletePreparedRows(session, preparedTokens);
              return null;
            });
      } catch (IOException | RuntimeException ignored) {
        // Rollback is best-effort and must not mask the original JGit exception.
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

  private boolean shouldPrePersist(
      List<Publication> publications, Collection<DfsPackDescription> replaces) {
    if (replaces != null && !replaces.isEmpty()) {
      return false;
    }
    boolean chunked = false;
    long totalPayloadBytes = 0;
    for (Publication publication : publications) {
      for (ExpectedExtension expected : publication.extensions()) {
        if (expected.staged() == null) {
          return false;
        }
        long extensionBytes = expected.staged().fileSize();
        totalPayloadBytes = saturatingAdd(totalPayloadBytes, extensionBytes);
        if (extensionBytes > HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD) {
          chunked = true;
        }
      }
    }
    return chunked && totalPayloadBytes >= minimumPrePersistedPayloadBytes;
  }

  private static long saturatingAdd(long left, long right) {
    if (right > Long.MAX_VALUE - left) {
      return Long.MAX_VALUE;
    }
    return left + right;
  }

  private static Set<String> publicationPackNames(List<Publication> publications) {
    Set<String> names = new LinkedHashSet<>();
    for (Publication publication : publications) {
      names.add(publication.packName());
    }
    return Set.copyOf(names);
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

  private void clearPreparedMappings(Set<String> packNames, String writeToken) {
    for (String packName : packNames) {
      preparedTokensByPackName.remove(packName, writeToken);
    }
  }

  private void discard(List<Publication> publications) {
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

  private byte[] readInline(FileChannel channel, long fileSize) throws IOException {
    byte[] data = new byte[Math.toIntExact(fileSize)];
    ByteBuffer destination = ByteBuffer.wrap(data);
    long position = 0;
    while (destination.hasRemaining()) {
      int count = channel.read(destination, position);
      if (count <= 0) {
        throw new IOException("Temporary pack file ended before declared size " + fileSize);
      }
      storageByteCounters.recordTemporaryFileBytesRead(count);
      position += count;
    }
    return data;
  }

  private void persistChunks(
      Session session, Long packId, FileChannel channel, long fileSize) throws IOException {
    long position = 0;
    int chunkIndex = 0;
    int pendingChunks = 0;
    while (position < fileSize) {
      int chunkLength =
          (int) Math.min(HibernateObjDatabase.PACK_CHUNK_SIZE, fileSize - position);
      byte[] chunkData = new byte[chunkLength];
      ByteBuffer destination = ByteBuffer.wrap(chunkData);
      long chunkPosition = position;
      while (destination.hasRemaining()) {
        int count = channel.read(destination, chunkPosition);
        if (count <= 0) {
          throw new IOException("Temporary pack file ended before declared size " + fileSize);
        }
        storageByteCounters.recordTemporaryFileBytesRead(count);
        chunkPosition += count;
      }

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(packId);
      chunk.setChunkIndex(chunkIndex);
      chunk.setChunkSize(chunkLength);
      // Hibernate retains this array until the bounded batch flush. Reading into the final array
      // avoids cloning a reusable one-MiB scratch buffer for every full chunk.
      chunk.setData(chunkData);
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

  private static long committedPayloadBytes(List<CommittedExtension> extensions) {
    long total = 0;
    for (CommittedExtension extension : extensions) {
      total = saturatingAdd(total, extension.fileSize());
    }
    return total;
  }

  private static long persistedPayloadBytes(List<PersistedExtension> extensions) {
    long total = 0;
    for (PersistedExtension extension : extensions) {
      total = saturatingAdd(total, extension.staged().fileSize());
    }
    return total;
  }

  private void deletePreparedRows(Session session, Collection<String> writeTokens) {
    if (writeTokens.isEmpty()) {
      return;
    }
    session
        .createMutationQuery(
            "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                + "AND p.committed = false AND p.writeToken IN :writeTokens")
        .setParameter("repo", repositoryName)
        .setParameter("writeTokens", writeTokens)
        .executeUpdate();
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

  private record PersistedExtension(
      StagedExtension staged,
      Long packId,
      boolean inline,
      byte[] inlineData,
      PackDescriptionMetadata metadata) {
    PersistedExtension {
      staged = Objects.requireNonNull(staged, "staged");
      packId = Objects.requireNonNull(packId, "packId");
      if (inline != (inlineData != null)) {
        throw new IllegalArgumentException(
            "inline payload must be present exactly for inline prepared rows");
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

  @FunctionalInterface
  interface PrePublicationHook {
    void afterPayloadPersisted(String writeToken) throws IOException;
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
    private final StorageByteCounters storageByteCounters;
    private final Path temporaryFile;
    private final FileChannel fileChannel;
    private final Instant createdAt = Instant.now();
    private long fileSize;
    private boolean closed;

    private StagedOutputStream(
        ExtensionKey key,
        StagingConsumer stagingConsumer,
        StorageByteCounters storageByteCounters)
        throws IOException {
      this.key = key;
      this.stagingConsumer = stagingConsumer;
      this.storageByteCounters = storageByteCounters;
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
        storageByteCounters.recordTemporaryFileBytesWritten(count);
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
        storageByteCounters.recordTemporaryFileBytesRead(count);
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
