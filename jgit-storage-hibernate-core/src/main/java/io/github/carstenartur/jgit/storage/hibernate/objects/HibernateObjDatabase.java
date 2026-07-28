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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * <p>Pack extensions are first written as uncommitted rows and become visible only when JGit calls
 * {@link #commitPackImpl(Collection, Collection)}. This mirrors the DFS contract more closely than
 * immediately exposing partially written pack files.
 *
 * <p>Small payloads are stored in the existing inline column to avoid an additional chunk row and
 * ORM round trip. Larger payloads are written through a temporary file and persisted as bounded
 * chunks. Existing inline and chunked rows remain readable without a destructive data migration.
 *
 * <p>Every uncommitted row has a writer token and renewable lease. Pack persistence, publication,
 * rollback and abandoned-write cleanup use the same repository lock so a maintenance operation
 * cannot race a live writer.
 *
 * <p>Shallow repositories are not supported. A non-empty shallow boundary is rejected explicitly
 * instead of being retained only in memory and silently lost on restart.
 */
public class HibernateObjDatabase extends DfsObjDatabase {

  static final int PACK_CHUNK_SIZE = 1024 * 1024;
  static final int INLINE_PAYLOAD_THRESHOLD = 256 * 1024;
  static final Duration PACK_WRITE_LEASE = Duration.ofMinutes(30);
  static final Duration PACK_LEASE_RENEWAL_INTERVAL = Duration.ofMinutes(5);
  private static final int CHUNK_BATCH_SIZE = 8;

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;

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

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    return transactionContext.execute(
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
    transactionContext.executeWithRepositoryLock(
        repositoryName,
        session -> {
          if (replaces != null) {
            for (DfsPackDescription replace : replaces) {
              deletePackRows(session, repositoryName, baseName(replace));
            }
          }
          Instant committedAt = Instant.now();
          for (DfsPackDescription description : descriptions) {
            int updated =
                session
                    .createMutationQuery(
                        "UPDATE GitPackEntity p SET p.committed = true, "
                            + "p.committedAt = :committedAt, p.writeToken = null, "
                            + "p.writeLeaseUntil = null WHERE p.repositoryName = :repo "
                            + "AND p.packName = :name AND p.committed = false")
                    .setParameter("committedAt", committedAt)
                    .setParameter("repo", repositoryName)
                    .setParameter("name", baseName(description))
                    .executeUpdate();
            if (updated == 0) {
              throw new IOException(
                  "Cannot publish missing or already committed pack " + baseName(description));
            }
          }
          return null;
        });
    clearCache();
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
    try {
      transactionContext.executeWithRepositoryLock(
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
        transactionContext, repositoryName, baseName(description), extension.getExtension());
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

  static final class HibernatePackOutputStream extends DfsOutputStream {
    private final HibernateTransactionContext transactionContext;
    private final String repositoryName;
    private final String packName;
    private final String packExtension;
    private final String writeToken = UUID.randomUUID().toString();
    private final Path temporaryFile;
    private final FileChannel fileChannel;
    private long fileSize;
    private int modificationVersion;
    private int persistedVersion = -1;
    private Instant renewLeaseAfter = Instant.MIN;
    private boolean closed;

    HibernatePackOutputStream(
        HibernateTransactionContext transactionContext,
        String repositoryName,
        String packName,
        String packExtension)
        throws IOException {
      this.transactionContext = transactionContext;
      this.repositoryName = repositoryName;
      this.packName = packName;
      this.packExtension = packExtension;
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
      renewLeaseIfDue();
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
      modificationVersion++;
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
      flush(false);
    }

    private void flush(boolean forceOwnershipCheck) throws IOException {
      ensureOpen();
      Instant now = Instant.now();
      boolean payloadChanged = persistedVersion != modificationVersion;
      boolean previouslyPersisted = persistedVersion >= 0;
      boolean leaseDue = !now.isBefore(renewLeaseAfter);
      if (!payloadChanged && !leaseDue && !forceOwnershipCheck) {
        return;
      }
      long sizeAtFlush = fileSize;
      transactionContext.executeWithRepositoryLock(
          repositoryName,
          session -> {
            GitPackEntity entity =
                session
                    .createQuery(
                        "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                            + "AND p.packName = :name AND p.packExtension = :ext",
                        GitPackEntity.class)
                    .setParameter("repo", repositoryName)
                    .setParameter("name", packName)
                    .setParameter("ext", packExtension)
                    .uniqueResult();

            boolean newEntity = entity == null;
            if (newEntity && previouslyPersisted) {
              throw new IOException(
                  "Pack writer ownership was lost for " + packName + "." + packExtension);
            }
            boolean rewritePayload = payloadChanged || newEntity;
            if (!newEntity) {
              if (entity.isCommitted()) {
                throw new IOException("Pack is already committed: " + packName + "." + packExtension);
              }
              String owner = entity.getWriteToken();
              if (owner != null && !writeToken.equals(owner)) {
                throw new IOException(
                    "Pack writer ownership was lost for " + packName + "." + packExtension);
              }
            } else {
              entity = new GitPackEntity();
              entity.setRepositoryName(repositoryName);
              entity.setPackName(packName);
              entity.setPackExtension(packExtension);
              entity.setCreatedAt(now);
              session.persist(entity);
            }

            boolean inlinePayload = sizeAtFlush <= INLINE_PAYLOAD_THRESHOLD;
            entity.setData(rewritePayload && inlinePayload ? readInlinePayload(sizeAtFlush) : null);
            entity.setFileSize(sizeAtFlush);
            entity.setCommitted(false);
            entity.setCommittedAt(null);
            entity.setWriteToken(writeToken);
            entity.setWriteLeaseUntil(now.plus(PACK_WRITE_LEASE));
            session.flush();

            if (rewritePayload && !inlinePayload) {
              Long packId = entity.getId();
              if (!newEntity) {
                session
                    .createMutationQuery("DELETE FROM GitPackChunkEntity c WHERE c.packId = :packId")
                    .setParameter("packId", packId)
                    .executeUpdate();
                session.flush();
              }
              persistChunks(session, packId, sizeAtFlush);
            }
            return null;
          });
      persistedVersion = modificationVersion;
      renewLeaseAfter = now.plus(PACK_LEASE_RENEWAL_INTERVAL);
    }

    private byte[] readInlinePayload(long sizeAtFlush) throws IOException {
      byte[] data = new byte[Math.toIntExact(sizeAtFlush)];
      ByteBuffer destination = ByteBuffer.wrap(data);
      long position = 0;
      while (destination.hasRemaining()) {
        int count = fileChannel.read(destination, position);
        if (count <= 0) {
          throw new IOException(
              "Temporary pack file ended before declared size " + sizeAtFlush);
        }
        position += count;
      }
      return data;
    }

    private void renewLeaseIfDue() throws IOException {
      if (persistedVersion >= 0 && !Instant.now().isBefore(renewLeaseAfter)) {
        flush(false);
      }
    }

    private void persistChunks(Session session, Long packId, long sizeAtFlush) throws IOException {
      byte[] chunkBuffer = new byte[PACK_CHUNK_SIZE];
      long position = 0;
      int chunkIndex = 0;
      int pendingChunks = 0;
      while (position < sizeAtFlush) {
        int chunkLength = (int) Math.min(PACK_CHUNK_SIZE, sizeAtFlush - position);
        ByteBuffer destination = ByteBuffer.wrap(chunkBuffer, 0, chunkLength);
        long chunkPosition = position;
        while (destination.hasRemaining()) {
          int count = fileChannel.read(destination, chunkPosition);
          if (count <= 0) {
            throw new IOException(
                "Temporary pack file ended before declared size " + sizeAtFlush);
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

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      IOException failure = null;
      try {
        flush(true);
      } catch (IOException exception) {
        failure = exception;
      }
      closed = true;
      try {
        fileChannel.close();
      } catch (IOException exception) {
        failure = combine(failure, exception);
      }
      try {
        Files.deleteIfExists(temporaryFile);
      } catch (IOException exception) {
        failure = combine(failure, exception);
      }
      if (failure != null) {
        throw failure;
      }
    }

    String writeToken() {
      return writeToken;
    }

    private static IOException combine(IOException primary, IOException additional) {
      if (primary == null) {
        return additional;
      }
      primary.addSuppressed(additional);
      return primary;
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
      // The channel caches one complete bounded chunk and does not prefetch additional rows.
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
    public int read(ByteBuffer destination) {
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
      position = Math.toIntExact(newPosition);
    }

    @Override
    public long size() {
      return data.length;
    }

    @Override
    public int blockSize() {
      return 0;
    }

    @Override
    public void setReadAheadBytes(int readAheadBytes) {
      // Byte array backed channel does not need read-ahead configuration.
    }
  }
}
