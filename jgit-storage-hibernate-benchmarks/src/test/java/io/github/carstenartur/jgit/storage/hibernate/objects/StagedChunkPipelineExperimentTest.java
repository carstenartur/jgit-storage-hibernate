/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.benchmark.BenchmarkConnectionProperties;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StagedChunkPipelineExperimentTest {
  private static final int CHUNK_BYTES = HibernateObjDatabase.PACK_CHUNK_SIZE;
  @TempDir Path scratch;

  @ParameterizedTest
  @EnumSource(StagedChunkPipelineExperiment.Mode.class)
  void recordsRollbackAfterAFlushedWindowForBothFailureDirections(
      StagedChunkPipelineExperiment.Mode mode) throws Exception {
    Path staged = scratch.resolve("rollback.pack");
    try (var output = Files.newOutputStream(staged)) {
      output.write(new byte[3 * CHUNK_BYTES + 1]);
    }
    for (var stage : StagedChunkPipelineExperiment.FailureStage.values()) {
      try (var provider = new HibernateSessionFactoryProvider(properties());
          var repository =
              HibernateRepository.create(provider.getSessionFactory(), "measured-rollback")) {
        repository.create(true);
        long packsBefore = count(provider, "GitPackEntity");
        long chunksBefore = count(provider, "GitPackChunkEntity");
        var result =
            StagedChunkPipelineExperiment.measureRollback(
                provider.getSessionFactory(), repository, staged, mode, 0, 1, stage);
        assertTrue(result.rollbackNanos() > 0);
        assertTrue(result.firstWindowFlushedNanos() > 0);
        assertTrue(result.failureDetectedNanos() >= result.firstWindowFlushedNanos());
        assertTrue(result.failureToReturnNanos() >= result.rollbackNanos());
        assertEquals(1, result.flushedChunks());
        assertEquals(packsBefore, count(provider, "GitPackEntity"));
        assertEquals(chunksBefore, count(provider, "GitPackChunkEntity"));
        assertEquals(0, result.remainingPacks());
        assertEquals(0, result.remainingChunks());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = StagedChunkPipelineExperiment.Mode.class,
      names = {"VIRTUAL", "PLATFORM"})
  void injectedReaderFailureWaitsForTheFirstWindowFlush(StagedChunkPipelineExperiment.Mode mode)
      throws Exception {
    var probe = new StagedChunkPipelineExperiment.FailureProbe();
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch readFinished = new CountDownLatch(1);
    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                StagedChunkPipelineExperiment.transfer(
                    (position, buffer) -> {
                      if (position == CHUNK_BYTES) {
                        readStarted.countDown();
                        try {
                          probe.failReader();
                        } finally {
                          readFinished.countDown();
                        }
                      }
                      return fill(position, buffer);
                    },
                    rows -> {
                      await(readStarted);
                      try {
                        assertFalse(
                            readFinished.await(100, TimeUnit.MILLISECONDS),
                            "the injected failure must wait while the first JDBC window is active");
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("writer test interrupted");
                      }
                      probe.flushed(rows.size());
                    },
                    1L,
                    3L * CHUNK_BYTES,
                    1,
                    mode));
    assertEquals("injected staging failure after the first window", actual.getMessage());
    assertTrue(probe.firstWindowFlushedAt() != 0);
    assertTrue(probe.detectedAt() >= probe.firstWindowFlushedAt());
  }

  @ParameterizedTest
  @EnumSource(
      value = StagedChunkPipelineExperiment.Mode.class,
      names = {"VIRTUAL", "PLATFORM"})
  void writerFailureCancelsInjectedReaderWaitingForFirstFlush(
      StagedChunkPipelineExperiment.Mode mode) {
    var probe = new StagedChunkPipelineExperiment.FailureProbe();
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    IOException writerFailure = new IOException("first JDBC window failed before flush completion");
    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                StagedChunkPipelineExperiment.transfer(
                    (position, buffer) -> {
                      if (position == CHUNK_BYTES) {
                        readStarted.countDown();
                        try {
                          probe.failReader();
                        } catch (InterruptedIOException interrupted) {
                          cancelled.countDown();
                          throw interrupted;
                        }
                      }
                      return fill(position, buffer);
                    },
                    rows -> {
                      await(readStarted);
                      throw writerFailure;
                    },
                    1L,
                    3L * CHUNK_BYTES,
                    1,
                    mode));
    assertSame(writerFailure, actual);
    assertEquals(0, cancelled.getCount(), "the blocked injected reader must terminate before return");
    assertEquals(0, probe.firstWindowFlushedAt());
    assertEquals(0, probe.detectedAt(), "cancellation must not fabricate the injected failure");
  }

  @ParameterizedTest
  @EnumSource(
      value = StagedChunkPipelineExperiment.Mode.class,
      names = {"VIRTUAL", "PLATFORM"})
  void nextWindowCanReadWhileWriterHoldsTheFirstWindow(StagedChunkPipelineExperiment.Mode mode)
      throws IOException {
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch readNext = new CountDownLatch(1);
    List<Integer> chunks = new ArrayList<>();

    var result =
        StagedChunkPipelineExperiment.transfer(
            (position, buffer) -> {
              if (position == CHUNK_BYTES) {
                await(writing);
                readNext.countDown();
              }
              return fill(position, buffer);
            },
            rows -> {
              var row = rows.getFirst();
              if (row.getChunkIndex() == 0) {
                writing.countDown();
                await(readNext);
              }
              chunks.add(row.getChunkIndex());
              assertEquals(row.getChunkIndex(), row.getData()[0]);
            },
            1L,
            2L * CHUNK_BYTES + 1,
            1,
            mode);

    assertEquals(List.of(0, 1, 2), chunks);
    assertEquals(2, result.retainedWindows());
  }

  @ParameterizedTest
  @EnumSource(
      value = StagedChunkPipelineExperiment.Mode.class,
      names = {"VIRTUAL", "PLATFORM"})
  void writerFailureCancelsAndJoinsAnActiveReader(StagedChunkPipelineExperiment.Mode mode) {
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    IOException writerFailure = new IOException("simulated database write failure");
    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                StagedChunkPipelineExperiment.transfer(
                    (position, buffer) -> {
                      if (position == CHUNK_BYTES) {
                        readStarted.countDown();
                        try {
                          await(new CountDownLatch(1));
                        } catch (InterruptedIOException interrupted) {
                          cancelled.countDown();
                          throw interrupted;
                        }
                      }
                      return fill(position, buffer);
                    },
                    rows -> {
                      await(readStarted);
                      throw writerFailure;
                    },
                    1L,
                    3L * CHUNK_BYTES,
                    1,
                    mode));
    assertSame(writerFailure, actual);
    assertEquals(0, cancelled.getCount(), "the reader terminated before failure was returned");
  }

  @ParameterizedTest
  @EnumSource(
      value = StagedChunkPipelineExperiment.Mode.class,
      names = {"VIRTUAL", "PLATFORM"})
  void readerFailureReachesTheWriterWithoutSubmittingLaterWindows(
      StagedChunkPipelineExperiment.Mode mode) {
    IOException readerFailure = new IOException("simulated staging read failure");
    List<Integer> writes = new ArrayList<>();
    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                StagedChunkPipelineExperiment.transfer(
                    (position, buffer) -> {
                      if (position >= CHUNK_BYTES) {
                        throw readerFailure;
                      }
                      return fill(position, buffer);
                    },
                    rows -> writes.add(rows.getFirst().getChunkIndex()),
                    1L,
                    3L * CHUNK_BYTES,
                    1,
                    mode));
    assertSame(readerFailure, actual);
    assertEquals(List.of(0), writes);
  }

  @ParameterizedTest
  @EnumSource(StagedChunkPipelineExperiment.Mode.class)
  void interruptionDuringTheFinalWriteFailsTheTransfer(StagedChunkPipelineExperiment.Mode mode) {
    try {
      assertThrows(
          InterruptedIOException.class,
          () ->
              StagedChunkPipelineExperiment.transfer(
                  StagedChunkPipelineExperimentTest::fill,
                  rows -> {
                    if (rows.getFirst().getChunkIndex() == 1) {
                      Thread.currentThread().interrupt();
                    }
                  },
                  1L,
                  2L * CHUNK_BYTES,
                  1,
                  mode));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void stagingFailureRollsBackThePackAndPreviouslyFlushedChunks() throws Exception {
    try (var provider = new HibernateSessionFactoryProvider(properties());
        var repository =
            HibernateRepository.create(provider.getSessionFactory(), "staging-failure")) {
      repository.create(true);
      long packsBefore = count(provider, "GitPackEntity");
      long chunksBefore = count(provider, "GitPackChunkEntity");
      var failure = new IOException("second window unavailable");

      assertSame(
          failure,
          assertThrows(
              IOException.class,
              () ->
                  StagedChunkPipelineExperiment.publish(
                      provider.getSessionFactory(),
                      repository,
                      2L * CHUNK_BYTES,
                      StagedChunkPipelineExperiment.Mode.VIRTUAL,
                      (position, buffer) -> {
                        if (position >= CHUNK_BYTES) {
                          throw failure;
                        }
                        return fill(position, buffer);
                      })));
      assertEquals(packsBefore, count(provider, "GitPackEntity"));
      assertEquals(chunksBefore, count(provider, "GitPackChunkEntity"));
    }
  }

  @Test
  void interruptionDuringSynchronousStagingRollsBackTheFlushedChunk() throws Exception {
    try (var provider = new HibernateSessionFactoryProvider(properties());
        var repository =
            HibernateRepository.create(provider.getSessionFactory(), "interrupted-staging")) {
      repository.create(true);
      long packsBefore = count(provider, "GitPackEntity");
      long chunksBefore = count(provider, "GitPackChunkEntity");

      try {
        assertThrows(
            InterruptedIOException.class,
            () ->
                StagedChunkPipelineExperiment.publish(
                    provider.getSessionFactory(),
                    repository,
                    2L * CHUNK_BYTES,
                    StagedChunkPipelineExperiment.Mode.SYNCHRONOUS,
                    (position, buffer) -> {
                      int read = fill(position, buffer);
                      if (position >= CHUNK_BYTES) {
                        Thread.currentThread().interrupt();
                      }
                      return read;
                    }));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(packsBefore, count(provider, "GitPackEntity"));
      assertEquals(chunksBefore, count(provider, "GitPackChunkEntity"));
    }
  }

  @Test
  void databaseConstraintFailureRollsBackTheWholePublication() throws Exception {
    try (var provider = new HibernateSessionFactoryProvider(properties());
        var repository = HibernateRepository.create(provider.getSessionFactory(), "jdbc-failure")) {
      repository.create(true);
      try (var session = provider.getSessionFactory().openSession()) {
        session.doWork(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute(
                    "ALTER TABLE git_pack_chunks ADD CONSTRAINT experiment_first_chunk"
                        + " CHECK (chunk_index = 0)");
              }
            });
      }
      long packsBefore = count(provider, "GitPackEntity");
      long chunksBefore = count(provider, "GitPackChunkEntity");

      assertThrows(
          ConstraintViolationException.class,
          () ->
              StagedChunkPipelineExperiment.publish(
                  provider.getSessionFactory(),
                  repository,
                  3L * CHUNK_BYTES,
                  StagedChunkPipelineExperiment.Mode.PLATFORM,
                  StagedChunkPipelineExperimentTest::fill));
      assertEquals(packsBefore, count(provider, "GitPackEntity"));
      assertEquals(chunksBefore, count(provider, "GitPackChunkEntity"));
    }
  }

  private static Properties properties() {
    BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
    var properties = new Properties();
    properties.setProperty(
        "hibernate.connection.url", "jdbc:hsqldb:mem:chunk-pipeline-" + UUID.randomUUID());
    properties.setProperty("hibernate.connection.username", "sa");
    properties.setProperty("hibernate.connection.password", "");
    properties.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    properties.setProperty("hibernate.search.enabled", "false");
    properties.setProperty(HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, "1");
    properties.setProperty(HibernateStorageSettings.JDBC_BATCH_SIZE, "1");
    return properties;
  }

  private static long count(HibernateSessionFactoryProvider provider, String entity) {
    try (var session = provider.getSessionFactory().openSession()) {
      return session
          .createSelectionQuery("SELECT count(e) FROM " + entity + " e", Long.class)
          .getSingleResult();
    }
  }

  private static int fill(long position, ByteBuffer buffer) {
    int bytes = buffer.remaining();
    buffer.put((byte) (position / CHUNK_BYTES));
    buffer.position(buffer.limit());
    return bytes;
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "the other stage did not signal");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      var failure = new InterruptedIOException("staging experiment interrupted");
      failure.initCause(interrupted);
      throw failure;
    }
  }
}
