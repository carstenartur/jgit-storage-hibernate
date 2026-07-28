/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
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
 * invalidation and prevents stale blocks with a different alignment from being reused by
 * copy-as-is protocol transfers.
 */
public final class ReadAheadHibernateObjDatabase extends HibernateObjDatabase {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;

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
            return new InlineReadableChannel(inlineData);
          }
          return new ReadAheadChunkedReadableChannel(sessionFactory, packId, fileSize);
        });
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    return new AlignedDfsOutputStream(super.writeFile(description, extension));
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
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
