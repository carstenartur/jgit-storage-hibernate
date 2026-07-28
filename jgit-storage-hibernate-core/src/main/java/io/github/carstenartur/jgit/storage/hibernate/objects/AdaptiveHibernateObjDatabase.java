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
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Hibernate object database which stores small pack extensions inline and appends large chunks
 * incrementally.
 *
 * <p>Small PACK, IDX and REFTABLE files are common for application-style commits. Persisting each of
 * them as a parent row plus a separate chunk row makes fixed transaction and ORM overhead dominate
 * the useful work. Files up to {@value #DEFAULT_INLINE_PAYLOAD_THRESHOLD} bytes therefore use the
 * existing inline payload column. Larger files retain bounded chunk storage.
 *
 * <p>The output stream is append-only. When JGit flushes a growing large file more than once, only
 * the previous partial chunk and newly appended chunks are rewritten instead of deleting and
 * persisting the complete file again.
 */
public final class AdaptiveHibernateObjDatabase extends HibernateObjDatabase {

  /** Default threshold below which one pack-related file is stored in the parent row. */
  public static final int DEFAULT_INLINE_PAYLOAD_THRESHOLD = 256 * 1024;

  private final HibernateTransactionContext transactionContext;
  private final String repositoryName;
  private final int inlinePayloadThreshold;

  public AdaptiveHibernateObjDatabase(
      DfsRepository repository,
      DfsReaderOptions options,
      SessionFactory sessionFactory,
      String repositoryName,
      HibernateTransactionContext transactionContext) {
    this(
        repository,
        options,
        sessionFactory,
        repositoryName,
        transactionContext,
        DEFAULT_INLINE_PAYLOAD_THRESHOLD);
  }

  AdaptiveHibernateObjDatabase(
      DfsRepository repository,
      DfsReaderOptions options,
      SessionFactory sessionFactory,
      String repositoryName,
      HibernateTransactionContext transactionContext,
      int inlinePayloadThreshold) {
    super(repository, options, sessionFactory, repositoryName, transactionContext);
    if (inlinePayloadThreshold < 0) {
      throw new IllegalArgumentException("inlinePayloadThreshold must not be negative");
    }
    this.transactionContext = transactionContext;
    this.repositoryName = repositoryName;
    this.inlinePayloadThreshold = inlinePayloadThreshold;
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    return new AdaptivePackOutputStream(
        transactionContext,
        repositoryName,
        baseName(description),
        extension.getExtension(),
        inlinePayloadThreshold);
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  static final class AdaptivePackOutputStream extends DfsOutputStream {
    private static final int CHUNK_BATCH_SIZE = 8;

    private final HibernateTransactionContext transactionContext;
    private final String repositoryName;
    private final String packName;
    private final String packExtension;
    private final int inlinePayloadThreshold;
    private final String writeToken = UUID.randomUUID().toString();
    private final Path temporaryFile;
    private final FileChannel fileChannel;
    private long fileSize;
    private long persistedSize = -1;
    private int modificationVersion;
    private int persistedVersion = -1;
    private Instant renewLeaseAfter = Instant.MIN;
    private boolean closed;

    AdaptivePackOutputStream(
        HibernateTransactionContext transactionContext,
        String repositoryName,
        String packName,
        String packExtension,
        int inlinePayloadThreshold)
        throws IOException {
      this.transactionContext = transactionContext;
      this.repositoryName = repositoryName;
      this.packName = packName;
      this.packExtension = packExtension;
      this.inlinePayloadThreshold = inlinePayloadThreshold;
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
            GitPackEntity entity = findEntity(session);
            boolean newEntity = entity == null;
            if (newEntity && previouslyPersisted) {
              throw new IOException(
                  "Pack writer ownership was lost for " + packName + "." + packExtension);
            }

            boolean wasChunked = false;
            long databaseSize = 0;
            if (entity != null) {
              if (entity.isCommitted()) {
                throw new IOException("Pack is already committed: " + packName + "." + packExtension);
              }
              String owner = entity.getWriteToken();
              if (owner != null && !writeToken.equals(owner)) {
                throw new IOException(
                    "Pack writer ownership was lost for " + packName + "." + packExtension);
              }
              databaseSize = entity.getFileSize();
              wasChunked = entity.getData() == null && databaseSize > 0;
            } else {
              entity = new GitPackEntity();
              entity.setRepositoryName(repositoryName);
              entity.setPackName(packName);
              entity.setPackExtension(packExtension);
              entity.setCreatedAt(now);
              session.persist(entity);
            }

            entity.setFileSize(sizeAtFlush);
            entity.setCommitted(false);
            entity.setCommittedAt(null);
            entity.setWriteToken(writeToken);
            entity.setWriteLeaseUntil(now.plus(PACK_WRITE_LEASE));

            if (payloadChanged || newEntity) {
              if (sizeAtFlush <= inlinePayloadThreshold) {
                persistInline(session, entity, sizeAtFlush, wasChunked);
              } else {
                long previousSize = persistedSize >= 0 ? persistedSize : databaseSize;
                persistChunked(
                    session, entity, sizeAtFlush, previousSize, wasChunked, newEntity);
              }
            }
            return null;
          });

      persistedSize = sizeAtFlush;
      persistedVersion = modificationVersion;
      renewLeaseAfter = now.plus(PACK_LEASE_RENEWAL_INTERVAL);
    }

    private GitPackEntity findEntity(Session session) {
      return session
          .createQuery(
              "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name AND p.packExtension = :ext",
              GitPackEntity.class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .setParameter("ext", packExtension)
          .uniqueResult();
    }

    private void persistInline(
        Session session, GitPackEntity entity, long sizeAtFlush, boolean wasChunked)
        throws IOException {
      if (wasChunked) {
        deleteChunksFrom(session, entity.getId(), 0);
      }
      entity.setData(readBytes(0, Math.toIntExact(sizeAtFlush)));
    }

    private void persistChunked(
        Session session,
        GitPackEntity entity,
        long sizeAtFlush,
        long previousSize,
        boolean wasChunked,
        boolean newEntity)
        throws IOException {
      entity.setData(null);
      session.flush();
      Long packId = entity.getId();

      long startPosition = 0;
      if (wasChunked && !newEntity && previousSize > 0) {
        int startChunkIndex = Math.toIntExact(previousSize / PACK_CHUNK_SIZE);
        if (previousSize % PACK_CHUNK_SIZE != 0) {
          deleteChunksFrom(session, packId, startChunkIndex);
          startPosition = (long) startChunkIndex * PACK_CHUNK_SIZE;
        } else {
          startPosition = previousSize;
        }
      }
      persistChunks(session, packId, startPosition, sizeAtFlush);
    }

    private static void deleteChunksFrom(Session session, Long packId, int firstChunkIndex) {
      session
          .createMutationQuery(
              "DELETE FROM GitPackChunkEntity c WHERE c.packId = :packId "
                  + "AND c.chunkIndex >= :firstChunkIndex")
          .setParameter("packId", packId)
          .setParameter("firstChunkIndex", firstChunkIndex)
          .executeUpdate();
      session.flush();
    }

    private void persistChunks(Session session, Long packId, long startPosition, long sizeAtFlush)
        throws IOException {
      byte[] chunkBuffer = new byte[PACK_CHUNK_SIZE];
      long position = startPosition;
      int chunkIndex = Math.toIntExact(startPosition / PACK_CHUNK_SIZE);
      int pendingChunks = 0;
      while (position < sizeAtFlush) {
        int chunkLength = (int) Math.min(PACK_CHUNK_SIZE, sizeAtFlush - position);
        readFully(position, chunkBuffer, chunkLength);

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

    private byte[] readBytes(long position, int length) throws IOException {
      byte[] result = new byte[length];
      readFully(position, result, length);
      return result;
    }

    private void readFully(long position, byte[] destination, int length) throws IOException {
      ByteBuffer buffer = ByteBuffer.wrap(destination, 0, length);
      long readPosition = position;
      while (buffer.hasRemaining()) {
        int count = fileChannel.read(buffer, readPosition);
        if (count <= 0) {
          throw new IOException("Temporary pack file ended before declared size " + fileSize);
        }
        readPosition += count;
      }
    }

    private void renewLeaseIfDue() throws IOException {
      if (persistedVersion >= 0 && !Instant.now().isBefore(renewLeaseAfter)) {
        flush(false);
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
}
