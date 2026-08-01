/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;

/**
 * Random-readable pack-extension staging that keeps tiny payloads in memory and spills once.
 *
 * <p>Retained in-memory staging is bounded by a deliberately narrow 16-KiB limit for each
 * extension and by one process-wide budget shared by every repository instance in this class
 * loader. A caller can additionally provide a narrower owner budget. The memory limit is lower than
 * the 256-KiB database inline threshold: measurements showed that retaining a larger prefix before a
 * multi-MiB spill regressed large-pack publication. When any bound is exceeded, the already written
 * prefix is copied once to a temporary file and all subsequent writes continue there. Positional
 * reads retain identical semantics before and after the spill.
 */
final class PackExtensionStagingBuffer extends DfsOutputStream {

  static final int MAX_MEMORY_BYTES = 16 * 1024;
  static final long PROCESS_MEMORY_BUDGET_BYTES = 32L * 1024 * 1024;
  private static final int INITIAL_CAPACITY = 1024;
  private static final MemoryBudget PROCESS_MEMORY_BUDGET =
      new MemoryBudget(PROCESS_MEMORY_BUDGET_BYTES);

  private final MemoryBudget ownerMemoryBudget;
  private final CloseConsumer closeConsumer;
  private final Instant createdAt = Instant.now();
  private byte[] memory = new byte[0];
  private Path temporaryFile;
  private FileChannel fileChannel;
  private long fileSize;
  private boolean closed;

  PackExtensionStagingBuffer(CloseConsumer closeConsumer) {
    this(new MemoryBudget(MAX_MEMORY_BYTES), closeConsumer);
  }

  PackExtensionStagingBuffer(MemoryBudget ownerMemoryBudget, CloseConsumer closeConsumer) {
    this.ownerMemoryBudget = Objects.requireNonNull(ownerMemoryBudget, "ownerMemoryBudget");
    this.closeConsumer = Objects.requireNonNull(closeConsumer, "closeConsumer");
  }

  @Override
  public void write(byte[] source, int offset, int length) throws IOException {
    ensureOpen();
    if (offset < 0 || length < 0 || offset > source.length - length) {
      throw new IndexOutOfBoundsException();
    }
    if (length == 0) {
      return;
    }
    if (fileSize > Long.MAX_VALUE - length) {
      throw new IOException("Pack extension exceeds the supported long file-size range");
    }

    long requiredSize = fileSize + length;
    if (fileChannel == null
        && requiredSize <= MAX_MEMORY_BYTES
        && ensureMemoryCapacity(Math.toIntExact(requiredSize))) {
      System.arraycopy(source, offset, memory, Math.toIntExact(fileSize), length);
      fileSize = requiredSize;
      return;
    }

    ensureFileBacked();
    ByteBuffer sourceBuffer = ByteBuffer.wrap(source, offset, length);
    long writePosition = fileSize;
    while (sourceBuffer.hasRemaining()) {
      int count = fileChannel.write(sourceBuffer, writePosition);
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
    if (fileChannel == null) {
      int count = (int) Math.min(destination.remaining(), fileSize - position);
      destination.put(memory, Math.toIntExact(position), count);
      return count;
    }
    return readFile(fileChannel, fileSize, position, destination);
  }

  @Override
  public void flush() throws IOException {
    ensureOpen();
    // Both memory writes and FileChannel writes are immediately visible to positional reads. Durable
    // database visibility is intentionally deferred to commitPack().
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;

    StagedPayload payload = null;
    IOException failure = null;
    if (fileChannel != null) {
      try {
        fileChannel.close();
        payload = new FilePayload(temporaryFile, fileSize);
        temporaryFile = null;
      } catch (IOException exception) {
        failure = exception;
      }
    } else {
      try {
        payload = finishMemoryPayload();
      } catch (RuntimeException | Error exception) {
        releaseMemory();
        throw exception;
      }
    }

    if (failure == null) {
      try {
        closeConsumer.accept(payload, fileSize, createdAt);
        return;
      } catch (IOException exception) {
        failure = exception;
      }
    }

    if (payload != null) {
      payload.discard();
    }
    if (temporaryFile != null) {
      try {
        Files.deleteIfExists(temporaryFile);
      } catch (IOException cleanupFailure) {
        if (failure == null) {
          failure = cleanupFailure;
        } else {
          failure.addSuppressed(cleanupFailure);
        }
      }
    }
    if (failure == null) {
      throw new IOException("Could not close pack extension staging");
    }
    throw failure;
  }

  boolean memoryBacked() {
    return fileChannel == null;
  }

  private boolean ensureMemoryCapacity(int requiredCapacity) {
    if (requiredCapacity <= memory.length) {
      return true;
    }
    int newCapacity = Math.max(INITIAL_CAPACITY, memory.length);
    while (newCapacity < requiredCapacity) {
      newCapacity = Math.min(MAX_MEMORY_BYTES, Math.multiplyExact(newCapacity, 2));
    }
    long additionalBytes = newCapacity - memory.length;
    if (!reserveMemory(additionalBytes)) {
      return false;
    }
    try {
      memory = Arrays.copyOf(memory, newCapacity);
      return true;
    } catch (RuntimeException | Error allocationFailure) {
      releaseMemory(additionalBytes);
      throw allocationFailure;
    }
  }

  private boolean reserveMemory(long bytes) {
    if (!ownerMemoryBudget.tryReserve(bytes)) {
      return false;
    }
    if (PROCESS_MEMORY_BUDGET.tryReserve(bytes)) {
      return true;
    }
    ownerMemoryBudget.release(bytes);
    return false;
  }

  private void releaseMemory(long bytes) {
    PROCESS_MEMORY_BUDGET.release(bytes);
    ownerMemoryBudget.release(bytes);
  }

  private void ensureFileBacked() throws IOException {
    if (fileChannel != null) {
      return;
    }

    Path candidate = Files.createTempFile("jgit-storage-pack-", ".tmp");
    FileChannel candidateChannel = null;
    try {
      candidateChannel =
          FileChannel.open(
              candidate,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING);
      ByteBuffer existing = ByteBuffer.wrap(memory, 0, Math.toIntExact(fileSize));
      long position = 0;
      while (existing.hasRemaining()) {
        int count = candidateChannel.write(existing, position);
        if (count <= 0) {
          throw new IOException("Could not spill staged pack bytes to a temporary file");
        }
        position += count;
      }
      fileChannel = candidateChannel;
      temporaryFile = candidate;
      candidateChannel = null;
      releaseMemory();
    } catch (IOException | RuntimeException exception) {
      if (candidateChannel != null) {
        try {
          candidateChannel.close();
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      try {
        Files.deleteIfExists(candidate);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }
  }

  private StagedPayload finishMemoryPayload() {
    int size = Math.toIntExact(fileSize);
    byte[] payloadBytes;
    if (memory.length == size) {
      payloadBytes = memory;
    } else {
      payloadBytes = Arrays.copyOf(memory, size);
      releaseMemory(memory.length - size);
    }
    memory = null;
    return new MemoryPayload(payloadBytes, ownerMemoryBudget);
  }

  private void releaseMemory() {
    if (memory != null && memory.length > 0) {
      releaseMemory(memory.length);
    }
    memory = new byte[0];
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("Pack output stream is closed");
    }
  }

  static long retainedMemoryBytes() {
    return PROCESS_MEMORY_BUDGET.usedBytes();
  }

  private static int readFile(
      FileChannel channel, long fileSize, long position, ByteBuffer destination) throws IOException {
    int total = 0;
    long readPosition = position;
    while (destination.hasRemaining() && readPosition < fileSize) {
      int originalLimit = destination.limit();
      int allowed = (int) Math.min(destination.remaining(), fileSize - readPosition);
      destination.limit(destination.position() + allowed);
      int count;
      try {
        count = channel.read(destination, readPosition);
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

  @FunctionalInterface
  interface CloseConsumer {
    void accept(StagedPayload payload, long fileSize, Instant createdAt) throws IOException;
  }

  interface StagedPayloadReader extends AutoCloseable {
    int read(long position, ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;
  }

  interface StagedPayload {
    byte[] inlineData() throws IOException;

    StagedPayloadReader openReader() throws IOException;

    boolean memoryBacked();

    void discard();
  }

  private static final class MemoryPayload implements StagedPayload {
    private final byte[] data;
    private final MemoryBudget ownerMemoryBudget;
    private final AtomicBoolean discarded = new AtomicBoolean();

    private MemoryPayload(byte[] data, MemoryBudget ownerMemoryBudget) {
      this.data = data;
      this.ownerMemoryBudget = ownerMemoryBudget;
    }

    @Override
    public byte[] inlineData() {
      return data;
    }

    @Override
    public StagedPayloadReader openReader() {
      return new StagedPayloadReader() {
        @Override
        public int read(long position, ByteBuffer destination) {
          if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
          }
          if (position >= data.length) {
            return -1;
          }
          int count = (int) Math.min(destination.remaining(), data.length - position);
          destination.put(data, Math.toIntExact(position), count);
          return count;
        }

        @Override
        public void close() {
          // No external resource.
        }
      };
    }

    @Override
    public boolean memoryBacked() {
      return true;
    }

    @Override
    public void discard() {
      if (discarded.compareAndSet(false, true)) {
        PROCESS_MEMORY_BUDGET.release(data.length);
        ownerMemoryBudget.release(data.length);
      }
    }
  }

  private static final class FilePayload implements StagedPayload {
    private final Path path;
    private final long fileSize;
    private final AtomicBoolean discarded = new AtomicBoolean();

    private FilePayload(Path path, long fileSize) {
      this.path = path;
      this.fileSize = fileSize;
    }

    @Override
    public byte[] inlineData() throws IOException {
      byte[] data = new byte[Math.toIntExact(fileSize)];
      ByteBuffer destination = ByteBuffer.wrap(data);
      try (StagedPayloadReader reader = openReader()) {
        long position = 0;
        while (destination.hasRemaining()) {
          int count = reader.read(position, destination);
          if (count <= 0) {
            throw new IOException("Temporary pack file ended before declared size " + fileSize);
          }
          position += count;
        }
      }
      return data;
    }

    @Override
    public StagedPayloadReader openReader() throws IOException {
      FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
      return new StagedPayloadReader() {
        @Override
        public int read(long position, ByteBuffer destination) throws IOException {
          if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
          }
          if (position >= fileSize) {
            return -1;
          }
          return readFile(channel, fileSize, position, destination);
        }

        @Override
        public void close() throws IOException {
          channel.close();
        }
      };
    }

    @Override
    public boolean memoryBacked() {
      return false;
    }

    @Override
    public void discard() {
      if (!discarded.compareAndSet(false, true)) {
        return;
      }
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // Unpublished derived state; stale files remain removable by the documented operator policy.
      }
    }
  }

  static final class MemoryBudget {
    private final long maxBytes;
    private final AtomicLong usedBytes = new AtomicLong();

    MemoryBudget(long maxBytes) {
      if (maxBytes < 0) {
        throw new IllegalArgumentException("maxBytes must not be negative");
      }
      this.maxBytes = maxBytes;
    }

    boolean tryReserve(long bytes) {
      if (bytes < 0) {
        throw new IllegalArgumentException("bytes must not be negative");
      }
      while (true) {
        long current = usedBytes.get();
        if (bytes > maxBytes - current) {
          return false;
        }
        if (usedBytes.compareAndSet(current, current + bytes)) {
          return true;
        }
      }
    }

    void release(long bytes) {
      if (bytes < 0) {
        throw new IllegalArgumentException("bytes must not be negative");
      }
      long remaining = usedBytes.addAndGet(-bytes);
      if (remaining < 0) {
        usedBytes.addAndGet(bytes);
        throw new IllegalStateException("Released more staging memory than was reserved");
      }
    }

    long usedBytes() {
      return usedBytes.get();
    }
  }
}
