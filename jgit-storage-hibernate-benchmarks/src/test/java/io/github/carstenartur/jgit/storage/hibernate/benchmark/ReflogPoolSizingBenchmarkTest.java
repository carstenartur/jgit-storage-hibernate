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
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue;
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue.Limits;
import io.github.carstenartur.jgit.storage.hibernate.refs.DurableReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendCommand;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
    var runs =
        coordinates.stream()
            .map(
                coordinate ->
                    new Run(coordinate, 0, 1, WARMUP_PER_PRODUCER, commandsPerProducer, 13))
            .toList();
    writeRuns(
        jdbcUrl, jdbcUrl, username, password, output, revision, "local-testcontainers", runs, null);
  }

  @Test
  @EnabledIfSystemProperty(named = PREFIX + "externalPlan", matches = ".+")
  void writesDisposablePostgresqlMatrix() throws Exception {
    String revision = System.getProperty(PREFIX + "revision", "");
    if (!revision.matches("[a-f0-9]{40}")) {
      throw new IllegalArgumentException(PREFIX + "revision must identify the source checkout");
    }
    String direct = System.getProperty(PREFIX + "directUrl", "");
    String routed = System.getProperty(PREFIX + "routedUrl", "");
    // The benchmark uses create-drop. Accept only a dedicated loopback database.
    for (String url : List.of(direct, routed)) {
      if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/jgit_reflog_sizing_disposable")) {
        throw new IllegalArgumentException("matrix requires the dedicated loopback database");
      }
    }
    var plan = Files.readAllLines(Path.of(System.getProperty(PREFIX + "externalPlan")));
    var runs = new ArrayList<Run>();
    for (String row : plan) {
      if (row.isBlank() || row.startsWith("#")) {
        continue;
      }
      String[] f = row.split(",", -1);
      if (f.length != 9) {
        throw new IllegalArgumentException(
            "plan needs stripes,pool,producers,scope,rtt,repeat,warmup,measured,messageChars");
      }
      runs.add(
          new Run(
              coordinates(String.join(",", f[0], f[1], f[2], f[3])).getFirst(),
              Integer.parseInt(f[4]),
              Integer.parseInt(f[5]),
              Integer.parseInt(f[6]),
              Integer.parseInt(f[7]),
              Integer.parseInt(f[8])));
    }
    assertTrue(!runs.isEmpty());
    assertEquals(runs.size(), runs.stream().map(Run::id).distinct().count());
    String upstream = System.getProperty(PREFIX + "toxiproxy", "");
    try (var shaper = new NetworkShaper(upstream)) {
      writeRuns(
          direct + "?sslmode=disable&connectTimeout=10&socketTimeout=20",
          routed + "?sslmode=disable&connectTimeout=10&socketTimeout=20",
          "benchmark",
          "",
          Path.of(System.getProperty(PREFIX + "externalOutput")),
          revision,
          "disposable-loopback",
          runs,
          shaper);
    }
  }

  private static void writeRuns(
      String direct,
      String routed,
      String username,
      String password,
      Path output,
      String revision,
      String deployment,
      List<Run> runs,
      NetworkShaper shaper)
      throws Exception {
    Files.createDirectories(output);
    Path raw = output.resolve("native.ndjson");
    Files.deleteIfExists(raw);
    Path acknowledgementFile = output.resolve("acknowledgements.csv");
    Path poolFile = output.resolve("pool-borrows.csv");
    Path calibrationFile = output.resolve("rtt-calibration.csv");
    Path occupancyFile = output.resolve("occupancy.csv");
    Files.writeString(
        acknowledgementFile,
        "coordinate,producer,ordinal,batch_size,queue_wait_nanos,ack_nanos,batch_id\n");
    Files.writeString(
        poolFile, "coordinate,worker_thread_id,acquisition_nanos,batch_id,hold_nanos\n");
    if (shaper != null) {
      Files.writeString(calibrationFile, "coordinate,ordinal,select_1_nanos\n");
      Files.writeString(
          occupancyFile,
          "coordinate,offset_nanos,pool_total,pool_active,pool_waiters,heap_used_bytes,queue_depth,queued_bytes,db_lock_waiters\n");
    }
    try (var telemetry =
        DatabaseTelemetryCollectors.create("postgresql", true, direct, username, password)) {
      for (Run run : runs) {
        Coordinate coordinate = run.coordinate();
        String id = shaper == null ? coordinate.id() : run.id();
        System.out.println("Reflog sizing: " + id);
        Measurement measurement =
            measure(
                run.rtt() == 0 ? direct : routed,
                username,
                password,
                telemetry,
                coordinate,
                run.warmup(),
                run.measured(),
                run.messageChars(),
                run.rtt(),
                shaper,
                direct);
        assertTrue(
            measurement.nativeDelta().counters().getOrDefault("postgresql.wal.insert_lsn_bytes", 0L)
                > 0,
            "every measured coordinate must advance the PostgreSQL WAL insert position");
        var acknowledgements = new StringBuilder();
        var pool = new StringBuilder();
        var calibration = new StringBuilder();
        var occupancy = new StringBuilder();
        for (Acknowledgement ack : measurement.acknowledgements()) {
          appendCsv(
              acknowledgements,
              id,
              ack.producer(),
              ack.ordinal(),
              ack.batchSize(),
              ack.queueWaitNanos(),
              ack.ackNanos(),
              ack.batchId());
        }
        for (PoolBorrow borrow : measurement.poolBorrows()) {
          appendCsv(
              pool,
              id,
              borrow.threadId(),
              borrow.acquisitionNanos(),
              borrow.id(),
              borrow.heldNanos());
        }
        int ordinal = 0;
        for (long roundTrip : measurement.roundTrips()) {
          appendCsv(calibration, id, ++ordinal, roundTrip);
        }
        for (Occupancy sample : measurement.occupancy()) {
          appendCsv(
              occupancy,
              id,
              sample.offsetNanos(),
              sample.poolTotal(),
              sample.poolActive(),
              sample.poolWaiters(),
              sample.heapBytes(),
              sample.queueDepth(),
              sample.queuedBytes(),
              sample.dbLockWaiters());
        }
        Files.writeString(acknowledgementFile, acknowledgements, StandardOpenOption.APPEND);
        Files.writeString(poolFile, pool, StandardOpenOption.APPEND);
        if (shaper != null) {
          Files.writeString(calibrationFile, calibration, StandardOpenOption.APPEND);
          Files.writeString(occupancyFile, occupancy, StandardOpenOption.APPEND);
        }
        DatabaseTelemetryJson.appendNdjson(
            raw,
            new DatabaseTelemetryObservation(
                Map.ofEntries(
                    Map.entry("id", id),
                    Map.entry("revision", revision),
                    Map.entry("javaVersion", Runtime.version().toString()),
                    Map.entry("deployment", deployment),
                    Map.entry("repositoryScope", coordinate.scope()),
                    Map.entry("stripes", Integer.toString(coordinate.stripes())),
                    Map.entry("poolSize", Integer.toString(coordinate.poolSize())),
                    Map.entry("producers", Integer.toString(coordinate.producers())),
                    Map.entry("requestedRttMillis", Integer.toString(run.rtt())),
                    Map.entry("repeat", Integer.toString(run.repeat())),
                    Map.entry("messageChars", Integer.toString(run.messageChars())),
                    Map.entry("warmupCommandsPerProducer", Integer.toString(run.warmup())),
                    Map.entry("measuredCommandsPerProducer", Integer.toString(run.measured())),
                    Map.entry("elapsedNanos", Long.toString(measurement.elapsedNanos())),
                    Map.entry("committedBatches", Long.toString(measurement.committedBatches())),
                    Map.entry("rejectedSubmissions", Long.toString(measurement.rejected())),
                    Map.entry("activeStripes", Long.toString(measurement.activeStripes()))),
                measurement.nativeDelta()));
      }
    }
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
    return measure(
        jdbcUrl, username, password, telemetry, coordinate, warmup, measured, 13, 0, null, jdbcUrl);
  }

  private static Measurement measure(
      String jdbcUrl,
      String username,
      String password,
      DatabaseTelemetryCollector telemetry,
      Coordinate coordinate,
      int warmup,
      int measured,
      int messageChars,
      int rtt,
      NetworkShaper shaper,
      String directUrl)
      throws Exception {
    var tracker = new BatchTracker();
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(jdbcUrl);
    hikari.setUsername(username);
    hikari.setPassword(password);
    hikari.setMaximumPoolSize(coordinate.poolSize());
    hikari.setMinimumIdle(coordinate.poolSize());
    hikari.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
    hikari.setMetricsTrackerFactory((poolName, stats) -> tracker);
    if (shaper != null) {
      hikari.addDataSourceProperty("ApplicationName", "jgit-reflog-sizing");
    }
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
        chains.add(commands(producer, warmup + measured, messageChars));
      }
      warmPool(pool, coordinate.poolSize());
      if (shaper != null) {
        shaper.delay(rtt);
      }
      List<Long> roundTrips;
      Phase phase;
      DatabaseTelemetryDelta delta;
      DurableStripedWriteQueue.Metrics queueBefore;
      List<Occupancy> occupancy;
      try {
        roundTrips =
            shaper == null ? List.of() : shaper.calibrate(jdbcUrl, username, password, rtt);
        execute(producers, writer, coordinate, chains, 0, warmup);
        queueBefore = writer.metrics();
        var databaseBefore = telemetry.capture();
        tracker.measuring.set(true);
        try (var sampler =
            shaper == null
                ? null
                : new OccupancySampler(directUrl, username, password, pool, writer)) {
          phase = execute(producers, writer, coordinate, chains, warmup, measured, tracker);
          occupancy = sampler == null ? List.of() : sampler.finish();
        } finally {
          tracker.measuring.set(false);
        }
        delta = databaseBefore.deltaTo(telemetry.capture());
      } finally {
        if (shaper != null) {
          shaper.delay(0);
        }
      }
      var queueAfter = writer.metrics();
      long committedBatches = queueAfter.batches() - queueBefore.batches();
      assertEquals(
          (long) coordinate.producers() * measured,
          queueAfter.completed() - queueBefore.completed());
      assertEquals(queueBefore.failed(), queueAfter.failed());
      long rejected = queueAfter.rejected() - queueBefore.rejected();
      assertEquals(0, rejected);
      var poolBorrows = tracker.borrows;
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
          roundTrips,
          occupancy,
          rejected,
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
    return commands(producer, count, 13);
  }

  private static List<ReflogAppendCommand> commands(int producer, int count, int messageChars) {
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
              messageChars == 13 ? "reflog sizing" : longMessage(producer, ordinal, messageChars)));
      oldId = newId;
    }
    return List.copyOf(commands);
  }

  private static String longMessage(int producer, int ordinal, int size) {
    // Deterministic but not a repeated, highly compressible string.
    var random = new java.util.Random(((long) producer << 32) + ordinal);
    var message = new StringBuilder(size);
    for (int index = 0; index < size; index++) {
      message.append((char) ('!' + random.nextInt(94)));
    }
    return message.toString();
  }

  private static Phase execute(
      ExecutorService pool,
      DurableReflogWriter writer,
      Coordinate coordinate,
      List<List<ReflogAppendCommand>> chains,
      int offset,
      int commands)
      throws Exception {
    return execute(pool, writer, coordinate, chains, offset, commands, null);
  }

  private static Phase execute(
      ExecutorService pool,
      DurableReflogWriter writer,
      Coordinate coordinate,
      List<List<ReflogAppendCommand>> chains,
      int offset,
      int commands,
      BatchTracker tracker)
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
                    TimedResult observed;
                    try {
                      observed =
                          completion
                              .thenApply(
                                  result ->
                                      new TimedResult(
                                          result,
                                          System.nanoTime() - submitted,
                                          tracker == null ? 0 : tracker.batchId(submitted)))
                              .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException | TimeoutException failure) {
                      completion.cancel(false);
                      throw failure;
                    }
                    ReflogAppendResult result = observed.result();
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
                            observed.elapsedNanos(),
                            observed.batchId()));
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
      int producer, int ordinal, int batchSize, long queueWaitNanos, long ackNanos, long batchId) {}

  private record TimedResult(ReflogAppendResult result, long elapsedNanos, long batchId) {}

  private record PoolBorrow(
      long id, long threadId, long acquiredAt, long acquisitionNanos, long heldNanos) {}

  private static final class BatchTracker implements IMetricsTracker {
    private final AtomicBoolean measuring = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadLocal<PoolBorrow> last = new ThreadLocal<>();
    private final ConcurrentLinkedQueue<PoolBorrow> borrows = new ConcurrentLinkedQueue<>();

    @Override
    public void recordConnectionAcquiredNanos(long elapsedNanos) {
      if (measuring.get()) {
        last.set(
            new PoolBorrow(
                sequence.incrementAndGet(),
                Thread.currentThread().threadId(),
                System.nanoTime(),
                elapsedNanos,
                -1));
      }
    }

    @Override
    public void recordConnectionUsageMillis(long ignoredMillis) {
      PoolBorrow opened = last.get();
      if (measuring.get() && opened != null) {
        PoolBorrow closed =
            new PoolBorrow(
                opened.id(),
                opened.threadId(),
                opened.acquiredAt(),
                opened.acquisitionNanos(),
                System.nanoTime() - opened.acquiredAt());
        last.set(closed);
        borrows.add(closed);
      }
    }

    long batchId(long submittedAt) {
      PoolBorrow borrow = last.get();
      // A callback attached after completion executes on its producer rather than its writer.
      // Leave that row unpaired; do not guess from timestamp order.
      return borrow != null && borrow.acquiredAt() > submittedAt && borrow.heldNanos() >= 0
          ? borrow.id()
          : 0;
    }
  }

  private record Run(
      Coordinate coordinate, int rtt, int repeat, int warmup, int measured, int messageChars) {
    Run {
      if (!List.of(0, 5, 20, 50).contains(rtt)
          || repeat < 1
          || warmup < 1
          || warmup > 128
          || measured < 1
          || measured > 1024
          || !List.of(13, 2000).contains(messageChars)) {
        throw new IllegalArgumentException("unsupported reflog sizing run");
      }
    }

    String id() {
      return coordinate.id() + "-rtt" + rtt + "-m" + messageChars + "-r" + repeat;
    }
  }

  private record Occupancy(
      long offsetNanos,
      int poolTotal,
      int poolActive,
      int poolWaiters,
      long heapBytes,
      long queueDepth,
      long queuedBytes,
      int dbLockWaiters) {}

  private static final class OccupancySampler implements AutoCloseable {
    private final long start = System.nanoTime();
    private final List<Occupancy> samples = new ArrayList<>();
    private final java.sql.Connection connection;
    private final java.sql.PreparedStatement query;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Future<?> task;
    private final AtomicBoolean done = new AtomicBoolean();

    OccupancySampler(
        String url,
        String username,
        String password,
        HikariDataSource pool,
        DurableReflogWriter writer)
        throws Exception {
      connection = DriverManager.getConnection(url, username, password);
      query =
          connection.prepareStatement(
              "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'jgit-reflog-sizing'"
                  + " AND wait_event_type = 'Lock'");
      var mx = pool.getHikariPoolMXBean();
      var heap = ManagementFactory.getMemoryMXBean();
      task =
          executor.submit(
              () -> {
                while (!done.get()) {
                  long offset = System.nanoTime() - start;
                  int waiters;
                  try (var result = query.executeQuery()) {
                    result.next();
                    waiters = result.getInt(1);
                  }
                  var metrics = writer.metrics();
                  samples.add(
                      new Occupancy(
                          offset,
                          mx.getTotalConnections(),
                          mx.getActiveConnections(),
                          mx.getThreadsAwaitingConnection(),
                          heap.getHeapMemoryUsage().getUsed(),
                          metrics.currentQueueDepth(),
                          metrics.currentQueuedBytes(),
                          waiters));
                  Thread.sleep(20);
                }
                return null;
              });
    }

    List<Occupancy> finish() throws Exception {
      done.set(true);
      task.get(5, TimeUnit.SECONDS);
      return List.copyOf(samples);
    }

    @Override
    public void close() throws Exception {
      try {
        finish();
      } finally {
        executor.shutdownNow();
        try (connection;
            query) {
          // The sampler has stopped using these resources.
        }
      }
    }
  }

  private static final class NetworkShaper implements AutoCloseable {
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String api;
    private int activeRtt;

    NetworkShaper(String api) {
      if (!api.matches("http://127\\.0\\.0\\.1:[0-9]+/proxies/[a-zA-Z0-9-]+")) {
        throw new IllegalArgumentException("matrix requires a dedicated loopback Toxiproxy route");
      }
      this.api = api + "/toxics";
    }

    void delay(int rtt) throws Exception {
      if (activeRtt == rtt) {
        return;
      }
      if (activeRtt > 0) {
        request("/sizing-upstream", null);
        request("/sizing-downstream", null);
      }
      activeRtt = rtt;
      if (rtt > 0) {
        for (String stream : List.of("upstream", "downstream")) {
          request(
              "",
              """
              {"name":"sizing-%s","type":"latency","stream":"%s","attributes":{"latency":%d,"jitter":0}}
              """
                  .formatted(stream, stream, "upstream".equals(stream) ? rtt / 2 : rtt - rtt / 2));
        }
      }
    }

    List<Long> calibrate(String url, String username, String password, int rtt) throws Exception {
      try (var connection = DriverManager.getConnection(url, username, password);
          var statement = connection.prepareStatement("SELECT 1")) {
        var values = new ArrayList<Long>();
        for (int ordinal = -5; ordinal < 20; ordinal++) {
          long started = System.nanoTime();
          try (var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
          }
          if (ordinal >= 0) {
            values.add(System.nanoTime() - started);
          }
        }
        double median = values.stream().sorted().toList().get(10) / 1_000_000.0;
        assertTrue(
            rtt == 0 ? median < 5 : median >= rtt * 0.9 && median <= rtt + 8,
            "unexpected SELECT 1 median: " + median + " ms for requested " + rtt);
        return List.copyOf(values);
      }
    }

    private void request(String suffix, String json) throws Exception {
      var builder = HttpRequest.newBuilder(URI.create(api + suffix)).timeout(Duration.ofSeconds(5));
      var request =
          json == null
              ? builder.DELETE()
              : builder
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(json));
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    }

    @Override
    public void close() throws Exception {
      try {
        delay(0);
      } finally {
        client.close();
      }
    }
  }

  private record Phase(long elapsedNanos, List<Acknowledgement> acknowledgements) {}

  private record Measurement(
      long elapsedNanos,
      long committedBatches,
      long activeStripes,
      List<Acknowledgement> acknowledgements,
      List<PoolBorrow> poolBorrows,
      List<Long> roundTrips,
      List<Occupancy> occupancy,
      long rejected,
      DatabaseTelemetryDelta nativeDelta) {}
}
