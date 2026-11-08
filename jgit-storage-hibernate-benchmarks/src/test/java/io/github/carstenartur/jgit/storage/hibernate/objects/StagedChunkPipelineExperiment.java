/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/** Benchmark-only staged chunk transfer. The owning thread performs every database write. */
public final class StagedChunkPipelineExperiment {
  private static final int CHUNK_BYTES = HibernateObjDatabase.PACK_CHUNK_SIZE;

  private StagedChunkPipelineExperiment() {}

  public enum Mode {
    SYNCHRONOUS,
    VIRTUAL,
    PLATFORM
  }

  public enum FailureStage {
    READER,
    JDBC
  }

  public static Publication publish(
      SessionFactory factory,
      HibernateRepository repository,
      Path staged,
      Mode mode,
      long readBytesPerSecond)
      throws IOException {
    try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.READ)) {
      return publish(
          factory, repository, channel.size(), mode, pacedSource(channel, readBytesPerSecond));
    }
  }

  private static Source pacedSource(FileChannel channel, long readBytesPerSecond) {
    return (position, buffer) -> {
      long started = System.nanoTime();
      int count = channel.read(buffer, position);
      if (count > 0 && readBytesPerSecond > 0) {
        long delay =
            count * 1_000_000_000L / readBytesPerSecond - (System.nanoTime() - started);
        if (delay > 0) {
          try {
            Thread.sleep(Duration.ofNanos(delay));
          } catch (InterruptedException interrupted) {
            throw interrupted(interrupted);
          }
        }
      }
      return count;
    };
  }

  /** Measure detection-to-return and JDBC rollback separately, after one flushed window. */
  public static Rollback measureRollback(
      SessionFactory factory,
      HibernateRepository repository,
      Path staged,
      Mode mode,
      long readBytesPerSecond,
      int windowMiB,
      FailureStage stage)
      throws IOException {
    if (windowMiB != HibernateStorageSettings.resolvePackChunkBatchSize(factory.getProperties())) {
      throw new IllegalArgumentException("failure boundary must match the configured writer window");
    }
    long failurePosition = (long) windowMiB * CHUNK_BYTES;
    long packsBefore = count(factory, "GitPackEntity");
    long chunksBefore = count(factory, "GitPackChunkEntity");
    var probe = new FailureProbe();
    try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.READ)) {
      if (channel.size() <= failurePosition) {
        throw new IllegalArgumentException("rollback fixture must exceed one writer window");
      }
      boolean jdbcFailure = stage == FailureStage.JDBC;
      if (jdbcFailure) {
        constraint(
            factory,
            "ADD CONSTRAINT staging_pipeline_failure CHECK (chunk_index < " + windowMiB + ")");
      }
      long returned;
      try {
        Source source = pacedSource(channel, readBytesPerSecond);
        try {
          publish(
              factory,
              repository,
              channel.size(),
              mode,
              (position, target) -> {
                if (!jdbcFailure && position >= failurePosition) {
                  probe.detected();
                  throw new IOException("injected staging failure after the first window");
                }
                return source.read(position, target);
              },
              probe);
          throw new AssertionError("injected publication failure was not observed");
        } catch (IOException | ConstraintViolationException failure) {
          returned = System.nanoTime();
          if (jdbcFailure != (failure instanceof ConstraintViolationException)) {
            throw failure;
          }
        }
      } finally {
        if (jdbcFailure) {
          constraint(factory, "DROP CONSTRAINT staging_pipeline_failure");
        }
      }
      if (probe.detectedAt.get() == 0
          || probe.rollbackNanos <= 0
          || probe.flushedChunks != windowMiB) {
        throw new AssertionError("failure did not roll back exactly one previously flushed window");
      }
      long remainingPacks = count(factory, "GitPackEntity") - packsBefore;
      long remainingChunks = count(factory, "GitPackChunkEntity") - chunksBefore;
      if (remainingPacks != 0 || remainingChunks != 0) {
        throw new AssertionError("failed publication left pack or chunk rows behind");
      }
      return new Rollback(
          returned - probe.detectedAt.get(),
          probe.rollbackNanos,
          probe.flushedChunks,
          remainingPacks,
          remainingChunks);
    }
  }

  private static void constraint(SessionFactory factory, String command) {
    try (var session = factory.openSession()) {
      var transaction = session.beginTransaction();
      try {
        session.doWork(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE git_pack_chunks " + command);
              }
            });
        transaction.commit();
      } catch (RuntimeException failure) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw failure;
      }
    }
  }

  private static long count(SessionFactory factory, String entity) {
    try (var session = factory.openSession()) {
      return session
          .createSelectionQuery("SELECT count(e) FROM " + entity + " e", Long.class)
          .getSingleResult();
    }
  }

  static Publication publish(
      SessionFactory factory, HibernateRepository repository, long size, Mode mode, Source source)
      throws IOException {
    return publish(factory, repository, size, mode, source, null);
  }

  private static Publication publish(
      SessionFactory factory,
      HibernateRepository repository,
      long size,
      Mode mode,
      Source source,
      FailureProbe probe)
      throws IOException {
    var database = (HibernateObjDatabase) repository.getObjectDatabase();
    var description = database.newPack(PackSource.RECEIVE);
    String filename = description.getFileName(PackExt.PACK);
    var pack = new GitPackEntity();
    pack.setRepositoryName(repository.getDescription().getRepositoryName());
    pack.setPackName(filename.substring(0, filename.lastIndexOf('.')));
    pack.setPackExtension(PackExt.PACK.getExtension());
    pack.setFileSize(size);
    pack.setCreatedAt(Instant.now());
    pack.setCommittedAt(pack.getCreatedAt());
    pack.setCommitted(true);
    long started = System.nanoTime();
    try (var session = factory.openSession()) {
      var transaction = session.beginTransaction();
      try {
        session.persist(pack);
        session.flush();
        Transfer transfer;
        try (var writer = HibernatePackChunkWriter.open(session, size)) {
          transfer =
              transfer(
                  source,
                  rows -> {
                    try {
                      writer.insert(rows);
                      var last = rows.getLast();
                      if ((long) last.getChunkIndex() * CHUNK_BYTES + last.getChunkSize() == size) {
                        writer.close();
                      }
                      if (probe != null) {
                        probe.flushedChunks += rows.size();
                      }
                    } catch (RuntimeException failure) {
                      if (probe != null) {
                        probe.detected();
                      }
                      throw failure;
                    }
                  },
                  pack.getId(),
                  size,
                  writer.batchSize(),
                  mode);
        }
        checkInterrupted();
        transaction.commit();
        return new Publication(description, System.nanoTime() - started, transfer);
      } catch (IOException | RuntimeException | Error failure) {
        if (transaction.isActive()) {
          try {
            long rollbackStarted = System.nanoTime();
            transaction.rollback();
            if (probe != null) {
              probe.rollbackNanos = System.nanoTime() - rollbackStarted;
            }
          } catch (RuntimeException rollback) {
            failure.addSuppressed(rollback);
          }
        }
        throw failure;
      }
    }
  }

  public static long digestReopened(
      HibernateRepository reopened, DfsPackDescription description, MessageDigest digest)
      throws IOException {
    var database = (HibernateObjDatabase) reopened.getObjectDatabase();
    try (var channel = database.openFile(description, PackExt.PACK)) {
      ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
      long bytes = 0;
      for (int count; (count = channel.read(buffer)) != -1; ) {
        if (count == 0) {
          throw new IOException("pack channel made no progress");
        }
        bytes += count;
        digest.update(buffer.flip());
        buffer.clear();
      }
      return bytes;
    }
  }

  static Transfer transfer(
      Source source, Sink sink, Long packId, long fileSize, int batchSize, Mode mode)
      throws IOException {
    if (fileSize <= 0 || batchSize <= 0) {
      throw new IllegalArgumentException("positive file size and window size required");
    }
    int chunks = Math.toIntExact((fileSize - 1) / CHUNK_BYTES + 1);
    long started = System.nanoTime();
    List<Span> reads = new ArrayList<>();
    List<Span> writes = new ArrayList<>();
    Window current = new Window(Math.min(batchSize, chunks));
    boolean pipelined = mode != Mode.SYNCHRONOUS && chunks > batchSize;
    if (!pipelined) {
      for (int first = 0; first < chunks; ) {
        current.fill(source, packId, fileSize, first, started, reads);
        current.write(sink, started, writes);
        first += current.rows.size();
      }
    } else {
      Window spare = new Window(batchSize);
      ThreadFactory factory =
          mode == Mode.VIRTUAL
              ? Thread.ofVirtual().name("pack-experiment-reader").factory()
              : Thread.ofPlatform().name("pack-experiment-reader").factory();
      try (var reader =
          new ThreadPoolExecutor(
              1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), factory)) {
        Future<?> pending = prepare(reader, current, source, packId, fileSize, 0, started, reads);
        try {
          for (int first = 0; first < chunks; ) {
            long waiting = System.nanoTime();
            await(pending);
            writes.add(new Span("reader-wait", waiting - started, System.nanoTime() - waiting, 0));
            first += current.rows.size();
            pending =
                first < chunks
                    ? prepare(reader, spare, source, packId, fileSize, first, started, reads)
                    : null;
            current.write(sink, started, writes);
            Window completed = current;
            current = spare;
            spare = completed;
          }
        } finally {
          if (pending != null) {
            pending.cancel(true);
          }
        }
      }
    }
    int windows = pipelined ? 2 : 1;
    long bufferBytes = (long) windows * Math.min(batchSize, chunks) * CHUNK_BYTES;
    return new Transfer(
        windows,
        bufferBytes,
        Stream.concat(reads.stream(), writes.stream())
            .sorted(Comparator.comparingLong(Span::startNanos))
            .toList());
  }

  private static Future<?> prepare(
      ThreadPoolExecutor reader,
      Window window,
      Source source,
      Long packId,
      long fileSize,
      int first,
      long started,
      List<Span> reads) {
    return reader.submit(
        () -> {
          window.fill(source, packId, fileSize, first, started, reads);
          return null;
        });
  }

  private static void await(Future<?> pending) throws IOException {
    try {
      pending.get();
    } catch (InterruptedException interrupted) {
      throw interrupted(interrupted);
    } catch (ExecutionException failed) {
      switch (failed.getCause()) {
        case IOException failure -> throw failure;
        case RuntimeException failure -> throw failure;
        case Error failure -> throw failure;
        default -> throw new IOException("staging reader failed", failed.getCause());
      }
    }
  }

  private static InterruptedIOException interrupted(InterruptedException interrupted) {
    Thread.currentThread().interrupt();
    var failure = new InterruptedIOException("pack chunk transfer interrupted");
    failure.initCause(interrupted);
    return failure;
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("pack chunk transfer interrupted");
    }
  }

  @FunctionalInterface
  interface Source {
    int read(long position, ByteBuffer target) throws IOException;
  }

  @FunctionalInterface
  interface Sink {
    void write(List<GitPackChunkEntity> chunks) throws IOException;
  }

  public record Span(String phase, long startNanos, long durationNanos, long bytes) {}

  public record Transfer(int retainedWindows, long bufferBytesUpperBound, List<Span> spans) {}

  public record Publication(DfsPackDescription description, long elapsedNanos, Transfer transfer) {}

  public record Rollback(
      long failureToReturnNanos,
      long rollbackNanos,
      int flushedChunks,
      long remainingPacks,
      long remainingChunks) {}

  private static final class FailureProbe {
    private final AtomicLong detectedAt = new AtomicLong();
    private long rollbackNanos;
    private int flushedChunks;

    private void detected() {
      detectedAt.compareAndSet(0, System.nanoTime());
    }
  }

  private static final class Window {
    private final byte[][] buffers;
    private final List<GitPackChunkEntity> rows;

    private Window(int capacity) {
      buffers = new byte[capacity][];
      rows = new ArrayList<>(capacity);
    }

    private void fill(
        Source source, Long packId, long fileSize, int first, long started, List<Span> reads)
        throws IOException {
      long before = System.nanoTime();
      long firstPosition = (long) first * CHUNK_BYTES;
      long position = firstPosition;
      rows.clear();
      for (int i = 0; i < buffers.length && position < fileSize; i++) {
        int size = (int) Math.min(CHUNK_BYTES, fileSize - position);
        if (buffers[i] == null || buffers[i].length != size) {
          buffers[i] = null;
          buffers[i] = new byte[size];
        }
        ByteBuffer destination = ByteBuffer.wrap(buffers[i]);
        while (destination.hasRemaining()) {
          checkInterrupted();
          int read = source.read(position + destination.position(), destination);
          if (read <= 0) {
            throw new IOException("staged pack ended before declared size " + fileSize);
          }
        }
        var row = new GitPackChunkEntity();
        row.setPackId(packId);
        row.setChunkIndex(first + i);
        row.setChunkSize(size);
        row.setData(buffers[i]);
        rows.add(row);
        position += size;
      }
      checkInterrupted();
      reads.add(
          new Span(
              "staging-read",
              before - started,
              System.nanoTime() - before,
              position - firstPosition));
    }

    private void write(Sink sink, long started, List<Span> writes) throws IOException {
      checkInterrupted();
      long before = System.nanoTime();
      sink.write(rows);
      checkInterrupted();
      writes.add(new Span("jdbc-window", before - started, System.nanoTime() - before, 0));
    }
  }
}
