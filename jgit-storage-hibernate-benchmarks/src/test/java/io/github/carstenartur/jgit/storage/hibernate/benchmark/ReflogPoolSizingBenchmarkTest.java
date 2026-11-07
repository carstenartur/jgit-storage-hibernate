/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue.Limits;
import io.github.carstenartur.jgit.storage.hibernate.refs.DurableReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendCommand;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

/** Bounded closed-loop measurement of the production reflog writer and one shared Hikari pool. */
class ReflogPoolSizingBenchmarkTest {
  private static final String PREFIX = "jgit.storage.benchmark.reflog-batch.sizing.";
  static final String ENABLED = PREFIX + "enabled";
  private static final int WARMUP_PER_PRODUCER = 8;
  private static final long PHASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final Instant WHEN = Instant.parse("2026-08-22T00:00:00Z");

  @Test
  void measuresRealQueueCommandsAndPoolBorrowsInBothRepositoryLayouts() throws Exception {
    for (var coordinate : coordinates("")) {
      try (var telemetry = DatabaseTelemetryCollectors.disabled("hsqldb", "fixture-contract")) {
        var measurement =
            measure(
                "jdbc:hsqldb:mem:reflog-sizes-" + coordinate.id() + ";shutdown=true",
                "sa",
                "",
                telemetry,
                coordinate,
                2,
                4);
        assertEquals(16, measurement.acknowledgements().size());
        assertEquals(measurement.committedBatches(), measurement.poolBorrows().size());
        assertTrue(
            measurement.acknowledgements().stream()
                .allMatch(
                    ack -> ack.queueWaitNanos() >= 0 && ack.ackNanos() >= ack.queueWaitNanos()));
        assertTrue(measurement.elapsedNanos() > 0);
      }
    }
  }

  @Test
  void limitsTheManualCoordinateToTheInvestigationAxes() {
    assertEquals(8, coordinates("").size());
    assertEquals(
        new Coordinate(8, 16, 16, "independent"), coordinates("8,16,16,independent").getFirst());
    assertThrows(IllegalArgumentException.class, () -> coordinates("8,32,16,independent"));
    assertThrows(IllegalArgumentException.class, () -> coordinates("8,16,16,unknown"));
  }

  @Test
  void stopsTheSizingQueueWhenAProducerFails() throws Exception {
    var coordinate = new Coordinate(4, 2, 4, "independent");
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl("jdbc:hsqldb:mem:reflog-sizing-failure;shutdown=true");
    hikari.setUsername("sa");
    hikari.setMaximumPoolSize(2);
    try (var pool = new HikariDataSource(hikari);
        var provider = provider(pool);
        var writer =
            new DurableReflogWriter(provider.getSessionFactory(), Limits.productionDefaults(4));
        var producers = Executors.newFixedThreadPool(4)) {
      String existing = coordinate.repositoryName(0);
      try (var repository = HibernateRepository.create(provider.getSessionFactory(), existing)) {
        repository.create(true);
      }
      // The other producers have no repository lock row, so they fail inside the real processor.
      var chains = List.of(commands(0, 8), commands(1, 8), commands(2, 8), commands(3, 8));
      assertThrows(
          ExecutionException.class, () -> execute(producers, writer, coordinate, chains, 0, 8));
      assertThrows(
          RejectedExecutionException.class,
          () -> writer.append(existing, chains.getFirst().getFirst()));
    }
  }

  static void writeNativeEvidence(String jdbcUrl, String username, String password, Path output)
      throws Exception {
    String selection = System.getProperty(PREFIX + "coordinate", "");
    String revision = System.getProperty(PREFIX + "revision", "");
    if (!revision.matches("[a-f0-9]{40}")) {
      throw new IllegalArgumentException(PREFIX + "revision must identify the source checkout");
    }
    List<Coordinate> coordinates = coordinates(selection);
    int commandsPerProducer = selection.isBlank() ? 24 : 128;
    Files.createDirectories(output);
    Path raw = output.resolve("native.ndjson");
    Files.deleteIfExists(raw);
    StringBuilder acknowledgements =
        new StringBuilder("coordinate,producer,ordinal,batch_size,queue_wait_nanos,ack_nanos\n");
    StringBuilder pool = new StringBuilder("coordinate,worker_thread_id,acquisition_nanos\n");
    try (var telemetry =
        DatabaseTelemetryCollectors.create("postgresql", true, jdbcUrl, username, password)) {
      for (Coordinate coordinate : coordinates) {
        Measurement measurement =
            measure(
                jdbcUrl,
                username,
                password,
                telemetry,
                coordinate,
                WARMUP_PER_PRODUCER,
                commandsPerProducer);
        assertTrue(
            measurement.nativeDelta().counters().getOrDefault("postgresql.wal.insert_lsn_bytes", 0L)
                > 0,
            "every measured coordinate must advance the PostgreSQL WAL insert position");
        for (Acknowledgement ack : measurement.acknowledgements()) {
          appendCsv(
              acknowledgements,
              coordinate.id(),
              ack.producer(),
              ack.ordinal(),
              ack.batchSize(),
              ack.queueWaitNanos(),
              ack.ackNanos());
        }
        for (PoolBorrow borrow : measurement.poolBorrows()) {
          appendCsv(pool, coordinate.id(), borrow.threadId(), borrow.acquisitionNanos());
        }
        DatabaseTelemetryJson.appendNdjson(
            raw,
            new DatabaseTelemetryObservation(
                Map.ofEntries(
                    Map.entry("id", coordinate.id()),
                    Map.entry("revision", revision),
                    Map.entry("javaVersion", Runtime.version().toString()),
                    Map.entry("deployment", "local-testcontainers"),
                    Map.entry("repositoryScope", coordinate.scope()),
                    Map.entry("stripes", Integer.toString(coordinate.stripes())),
                    Map.entry("poolSize", Integer.toString(coordinate.poolSize())),
                    Map.entry("producers", Integer.toString(coordinate.producers())),
                    Map.entry("warmupCommandsPerProducer", Integer.toString(WARMUP_PER_PRODUCER)),
                    Map.entry("measuredCommandsPerProducer", Integer.toString(commandsPerProducer)),
                    Map.entry("elapsedNanos", Long.toString(measurement.elapsedNanos())),
                    Map.entry("committedBatches", Long.toString(measurement.committedBatches())),
                    Map.entry("activeStripes", Long.toString(measurement.activeStripes()))),
                measurement.nativeDelta()));
      }
    }
    Files.writeString(output.resolve("acknowledgements.csv"), acknowledgements);
    Files.writeString(output.resolve("pool-borrows.csv"), pool);
    DatabaseTelemetryJson.writeAggregate(raw, output.resolve("native.json"));
  }

  private static void appendCsv(StringBuilder result, String coordinate, long... fields) {
    result.append(coordinate);
    for (long field : fields) {
      result.append(',').append(field);
    }
    result.append('\n');
  }

  private static Measurement measure(
      String jdbcUrl,
      String username,
      String password,
      DatabaseTelemetryCollector telemetry,
      Coordinate coordinate,
      int warmup,
      int measured)
      throws Exception {
    AtomicBoolean measuring = new AtomicBoolean();
    var poolBorrows = new ConcurrentLinkedQueue<PoolBorrow>();
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(jdbcUrl);
    hikari.setUsername(username);
    hikari.setPassword(password);
    hikari.setMaximumPoolSize(coordinate.poolSize());
    hikari.setMinimumIdle(coordinate.poolSize());
    hikari.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
    hikari.setMetricsTrackerFactory(
        (poolName, stats) ->
            new IMetricsTracker() {
              @Override
              public void recordConnectionAcquiredNanos(long elapsedNanos) {
                if (measuring.get()) {
                  poolBorrows.add(new PoolBorrow(Thread.currentThread().threadId(), elapsedNanos));
                }
              }
            });
    try (HikariDataSource pool = new HikariDataSource(hikari);
        HibernateSessionFactoryProvider provider = provider(pool);
        DurableReflogWriter writer =
            new DurableReflogWriter(
                provider.getSessionFactory(), Limits.productionDefaults(coordinate.stripes()));
        ExecutorService producers = Executors.newFixedThreadPool(coordinate.producers())) {
      List<List<ReflogAppendCommand>> chains = new ArrayList<>();
      for (int producer = 0; producer < coordinate.producers(); producer++) {
        if (producer == 0 || !coordinate.shared()) {
          try (HibernateRepository repository =
              HibernateRepository.create(
                  provider.getSessionFactory(), coordinate.repositoryName(producer))) {
            repository.create(true);
          }
        }
        chains.add(commands(producer, warmup + measured));
      }
      warmPool(pool, coordinate.poolSize());
      execute(producers, writer, coordinate, chains, 0, warmup);

      var queueBefore = writer.metrics();
      var databaseBefore = telemetry.capture();
      Phase phase;
      measuring.set(true);
      try {
        phase = execute(producers, writer, coordinate, chains, warmup, measured);
      } finally {
        measuring.set(false);
      }
      DatabaseTelemetryDelta delta = databaseBefore.deltaTo(telemetry.capture());
      var queueAfter = writer.metrics();
      long committedBatches = queueAfter.batches() - queueBefore.batches();
      assertEquals(
          (long) coordinate.producers() * measured,
          queueAfter.completed() - queueBefore.completed());
      assertEquals(queueBefore.failed(), queueAfter.failed());
      assertEquals(queueBefore.rejected(), queueAfter.rejected());
      assertEquals(
          committedBatches, poolBorrows.size(), "one pool borrow per measured transaction");
      assertEquals((long) coordinate.producers() * (warmup + measured), rowCount(provider));
      long activeStripes = poolBorrows.stream().mapToLong(PoolBorrow::threadId).distinct().count();
      assertEquals(
          coordinate.shared() ? 1 : Math.min(coordinate.stripes(), coordinate.producers()),
          activeStripes,
          "repository names should cover the intended writer stripes");
      return new Measurement(
          phase.elapsedNanos(),
          committedBatches,
          activeStripes,
          phase.acknowledgements(),
          List.copyOf(poolBorrows),
          delta);
    }
  }

  private static HibernateSessionFactoryProvider provider(HikariDataSource pool) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.datasource", pool);
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    properties.setProperty("hibernate.search.enabled", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  /**
   * Borrow all configured slots together before measuring so pool creation is outside the window.
   */
  private static void warmPool(HikariDataSource pool, int slots) throws Exception {
    if (slots > 0) {
      try (var ignored = pool.getConnection()) {
        warmPool(pool, slots - 1);
      }
    }
  }

  private static long rowCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(r) FROM GitReflogEntity r WHERE r.deliveryId IS NOT NULL", Long.class)
          .getSingleResult();
    }
  }

  private static List<ReflogAppendCommand> commands(int producer, int count) {
    List<ReflogAppendCommand> commands = new ArrayList<>(count);
    ObjectId oldId = ObjectId.zeroId();
    for (int ordinal = 1; ordinal <= count; ordinal++) {
      ObjectId newId = ObjectId.fromString("%040x".formatted(((long) producer << 32) + ordinal));
      commands.add(
          new ReflogAppendCommand(
              "sizing-" + producer + "-" + ordinal,
              "refs/heads/producer-" + producer,
              oldId,
              newId,
              "Sizing Fixture",
              "sizing@example.invalid",
              WHEN.plusMillis(ordinal),
              "reflog sizing"));
      oldId = newId;
    }
    return List.copyOf(commands);
  }

  private static Phase execute(
      ExecutorService pool,
      DurableReflogWriter writer,
      Coordinate coordinate,
      List<List<ReflogAppendCommand>> chains,
      int offset,
      int commands)
      throws Exception {
    long deadline = System.nanoTime() + PHASE_TIMEOUT_NANOS;
    CountDownLatch ready = new CountDownLatch(coordinate.producers());
    CountDownLatch start = new CountDownLatch(1);
    var completions = new ExecutorCompletionService<List<Acknowledgement>>(pool);
    List<Future<List<Acknowledgement>>> futures = new ArrayList<>();
    boolean finished = false;
    try {
      for (int producer = 0; producer < coordinate.producers(); producer++) {
        int id = producer;
        futures.add(
            completions.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException("reflog sizing start barrier exceeded its deadline");
                  }
                  List<Acknowledgement> acknowledgements = new ArrayList<>(commands);
                  for (int ordinal = 0; ordinal < commands; ordinal++) {
                    ReflogAppendCommand command = chains.get(id).get(offset + ordinal);
                    long submitted = System.nanoTime();
                    var submission = writer.append(coordinate.repositoryName(id), command);
                    var completion = submission.completion();
                    ReflogAppendResult result;
                    try {
                      result = completion.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException | TimeoutException failure) {
                      completion.cancel(false);
                      throw failure;
                    }
                    long elapsed = System.nanoTime() - submitted;
                    assertEquals(ReflogAppendResult.Status.APPENDED, result.status());
                    assertEquals(command.deliveryId(), result.deliveryId());
                    if (!coordinate.shared()) {
                      assertEquals(
                          1,
                          submission.batchSize(),
                          "independent repositories cannot batch together");
                    }
                    acknowledgements.add(
                        new Acknowledgement(
                            id,
                            ordinal,
                            submission.batchSize(),
                            submission.queueWaitNanos(),
                            elapsed));
                  }
                  return acknowledgements;
                }));
      }
      if (!ready.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
        throw new TimeoutException("reflog sizing producers did not reach the start barrier");
      }
      long started = System.nanoTime();
      start.countDown();
      List<Acknowledgement> acknowledgements = new ArrayList<>();
      for (int index = 0; index < futures.size(); index++) {
        var future = completions.poll(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        if (future == null) {
          throw new TimeoutException("reflog sizing phase exceeded its deadline");
        }
        acknowledgements.addAll(future.get());
      }
      long elapsed = System.nanoTime() - started;
      acknowledgements.sort(
          Comparator.comparingInt(Acknowledgement::producer)
              .thenComparingInt(Acknowledgement::ordinal));
      finished = true;
      return new Phase(elapsed, acknowledgements);
    } finally {
      if (!finished) {
        futures.forEach(future -> future.cancel(true));
        writer.shutdownNow();
      }
      start.countDown();
    }
  }

  private static long remainingNanos(long deadline) throws TimeoutException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException("reflog sizing phase exceeded its deadline");
    }
    return remaining;
  }

  private static List<Coordinate> coordinates(String selection) {
    if (!selection.isBlank()) {
      String[] fields = selection.strip().split(",", -1);
      if (fields.length != 4) {
        throw new IllegalArgumentException(
            "sizing coordinate must be stripes,pool,producers,scope");
      }
      return List.of(
          new Coordinate(
              Integer.parseInt(fields[0]),
              Integer.parseInt(fields[1]),
              Integer.parseInt(fields[2]),
              fields[3]));
    }
    List<Coordinate> smoke = new ArrayList<>();
    for (int stripes : List.of(1, 4)) {
      for (int pool : List.of(2, 4)) {
        for (String scope : List.of("shared", "independent")) {
          smoke.add(new Coordinate(stripes, pool, 4, scope));
        }
      }
    }
    return List.copyOf(smoke);
  }

  private record Coordinate(int stripes, int poolSize, int producers, String scope) {
    Coordinate {
      if (!List.of(1, 2, 4, 8).contains(stripes)
          || !List.of(2, 4, 8, 16).contains(poolSize)
          || !List.of(1, 4, 8, 16).contains(producers)
          || !List.of("shared", "independent").contains(scope)) {
        throw new IllegalArgumentException("unsupported reflog sizing coordinate");
      }
    }

    String id() {
      return "%s-s%d-p%d-w%d".formatted(scope, stripes, poolSize, producers);
    }

    boolean shared() {
      return "shared".equals(scope);
    }

    String repositoryName(int producer) {
      return shared() ? "sizing-shared" : "sizing-" + (char) ('a' + producer);
    }
  }

  private record Acknowledgement(
      int producer, int ordinal, int batchSize, long queueWaitNanos, long ackNanos) {}

  private record PoolBorrow(long threadId, long acquisitionNanos) {}

  private record Phase(long elapsedNanos, List<Acknowledgement> acknowledgements) {}

  private record Measurement(
      long elapsedNanos,
      long committedBatches,
      long activeStripes,
      List<Acknowledgement> acknowledgements,
      List<PoolBorrow> poolBorrows,
      DatabaseTelemetryDelta nativeDelta) {}
}
