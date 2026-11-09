/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static io.github.carstenartur.jgit.storage.hibernate.benchmark.StagingPipelineDiagnostics.checkpoint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedChunkPipelineExperiment;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedChunkPipelineExperiment.FailureStage;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedChunkPipelineExperiment.Mode;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedChunkPipelineExperiment.Publication;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedChunkPipelineExperiment.Span;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

class StagedChunkPipelineEvidenceTest {
  private static final String PREFIX = "jgit.storage.benchmark.staging-pipeline";
  private static final int MIB = 1024 * 1024;
  private static final String LOOPBACK_DATABASE =
      "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/jgit_stage_pipeline_disposable(?:\\?sslmode=disable)?";

  @TempDir Path scratch;

  @Test
  void allTransferModesCommitAndColdReopenIdenticalBytes() throws Exception {
    Fixture fixture = fixture(3);
    try (var telemetry = DatabaseTelemetryCollectors.disabled("hsqldb", "embedded-contract")) {
      for (Mode mode : Mode.values()) {
        Trial result =
            measure(
                connection(
                    "jdbc:hsqldb:mem:stage-pipeline", "sa", "", "org.hsqldb.jdbc.JDBCDriver", 1),
                telemetry,
                fixture,
                mode,
                1,
                0);
        assertEquals(
            mode == Mode.SYNCHRONOUS ? 1 : 2,
            result.publications().getFirst().transfer().retainedWindows());
      }
    }
  }

  @Test
  @EnabledIfSystemProperty(named = PREFIX + ".enabled", matches = "true")
  void recordsPostgresqlPipelineEvidence() throws Exception {
    checkpoint("native-test:begin");
    String routed = System.getenv("JGIT_STAGING_PIPELINE_DISPOSABLE_JDBC_URL");
    if (routed != null) {
      String direct = System.getenv().getOrDefault("JGIT_STAGING_PIPELINE_DIRECT_JDBC_URL", routed);
      if (!routed.matches(LOOPBACK_DATABASE) || !direct.matches(LOOPBACK_DATABASE)) {
        throw new IllegalArgumentException(
            "use a dedicated loopback jgit_stage_pipeline_disposable database");
      }
      record(routed, direct, "benchmark", "");
      return;
    }
    try (var network = Network.newNetwork();
        var postgres =
            new PostgreSQLContainer("postgres:17.10-alpine")
                .withDatabaseName("jgit_stage_pipeline_disposable")
                .withNetwork(network)
                .withNetworkAliases("staging-postgres")
                .withCommand(
                    "postgres",
                    "-c",
                    "fsync=on",
                    "-c",
                    "synchronous_commit=on",
                    "-c",
                    "track_io_timing=on",
                    "-c",
                    "track_wal_io_timing=on")) {
      checkpoint("postgres:start:begin");
      postgres.start();
      checkpoint("postgres:start:returned");
      int requestedRtt = Integer.getInteger(PREFIX + ".requested_rtt_millis", 0);
      if (requestedRtt == 0) {
        record(
            postgres.getJdbcUrl(),
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword());
      } else {
        try (var proxy =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(network)) {
          checkpoint("proxy:start:begin");
          proxy.start();
          checkpoint("proxy:start:returned");
          var client = new ToxiproxyClient(proxy.getHost(), proxy.getControlPort());
          var route =
              client.createProxy("staging-postgres", "0.0.0.0:8666", "staging-postgres:5432");
          route.toxics().latency("upstream", ToxicDirection.UPSTREAM, requestedRtt / 2);
          route
              .toxics()
              .latency("downstream", ToxicDirection.DOWNSTREAM, requestedRtt - requestedRtt / 2);
          record(
              "jdbc:postgresql://"
                  + proxy.getHost()
                  + ":"
                  + proxy.getMappedPort(8666)
                  + "/"
                  + postgres.getDatabaseName(),
              postgres.getJdbcUrl(),
              postgres.getUsername(),
              postgres.getPassword());
        }
      }
      checkpoint("containers:close:begin");
    } finally {
      checkpoint("native-test:returned");
    }
  }

  private void record(String url, String telemetryUrl, String user, String password)
      throws Exception {
    url = withPostgresqlTimeouts(url);
    telemetryUrl = withPostgresqlTimeouts(telemetryUrl);
    int payloadMiB = Integer.getInteger(PREFIX + ".payload_mib", 16);
    int windowMiB = Integer.getInteger(PREFIX + ".window_mib", 16);
    int writers = Integer.getInteger(PREFIX + ".writers", 1);
    int repeats = Integer.getInteger(PREFIX + ".repeats", 3);
    int warmups = Integer.getInteger(PREFIX + ".warmups", 1);
    int rateMiB = Integer.getInteger(PREFIX + ".read_mib_per_second", 0);
    int requestedRtt = Integer.getInteger(PREFIX + ".requested_rtt_millis", 0);
    assertTrue(List.of(16, 128, 512).contains(payloadMiB));
    assertTrue(List.of(16, 32).contains(windowMiB));
    assertTrue(List.of(1, 4, 16).contains(writers));
    assertTrue(List.of(0, 5, 20, 50).contains(requestedRtt));
    assertTrue(repeats > 0 && repeats <= 9 && warmups >= 0 && warmups <= 3 && rateMiB >= 0);
    checkpoint("fixture:create:begin");
    Fixture fixture = fixture(payloadMiB);
    checkpoint("fixture:create:returned");
    String storage = System.getProperty(PREFIX + ".storage", rateMiB > 0 ? "paced" : "runner");
    assertTrue(List.of("runner", "tmpfs", "paced").contains(storage));
    String fileSystem = Files.getFileStore(fixture.file()).type();
    assertTrue(!storage.equals("tmpfs") || fileSystem.equals("tmpfs"), "tmpfs cell must use tmpfs");
    assertTrue(!storage.equals("paced") || rateMiB > 0, "paced cell requires a read rate");
    checkpoint("calibration:begin");
    Calibration calibration = calibrate(url, user, password);
    checkpoint("calibration:returned");
    long rttNanos = calibration.medianNanos();
    double actualRtt = rttNanos / 1_000_000.0;
    assertTrue(
        requestedRtt == 0
            ? actualRtt < 5
            : actualRtt >= requestedRtt * 0.9 && actualRtt <= requestedRtt + 8,
        "SELECT 1 did not observe requested RTT: " + actualRtt);
    Path output = Path.of(System.getProperty(PREFIX + ".output", "target/staging-pipeline"));
    Files.createDirectories(output);
    var rtt = new StringBuilder("sample,rtt_nanos\n");
    for (int i = 0; i < calibration.samples().length; i++) {
      append(rtt, i + 1, calibration.samples()[i]);
    }
    Files.writeString(output.resolve("rtt-calibration.csv"), rtt);
    var environment = new Properties();
    environment.setProperty(
        "source_commit", System.getenv().getOrDefault("JGIT_EVIDENCE_SOURCE_SHA", "unrecorded"));
    environment.setProperty(
        "measurement_boundary",
        StagingPipelineDiagnostics.enabled()
            ? "diagnostic-only"
            : "staged-transfer-through-transaction-commit");
    environment.setProperty("java_version", System.getProperty("java.runtime.version"));
    environment.setProperty(
        "os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    environment.setProperty("storage", storage);
    environment.setProperty("staging_filesystem", fileSystem);
    environment.setProperty("staging_directory", fixture.file().getParent().toString());
    environment.setProperty("warmup_rounds", Integer.toString(warmups));
    environment.setProperty("repeats", Integer.toString(repeats));
    environment.setProperty("rollback_writers", "1");
    environment.setProperty("max_heap_bytes", Long.toString(Runtime.getRuntime().maxMemory()));
    environment.setProperty(
        "available_processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
    try (var sink = Files.newOutputStream(output.resolve("environment.properties"))) {
      environment.store(sink, "Native staging diagnostic; one fixture shared by all writers");
    }
    Path nativeFile = output.resolve("database-telemetry.ndjson");
    Files.deleteIfExists(nativeFile);
    var summary =
        new StringBuilder(
            "sample,mode,payload_bytes,window_mib,writers,requested_rtt_ms,rtt_nanos,read_mib_per_second,wall_nanos,cpu_nanos,allocated_bytes,heap_peak_upper_bytes,buffer_bound_bytes,staging_read_nanos,jdbc_window_nanos,reader_wait_nanos,overlap_nanos,staging_written_bytes,staging_read_bytes\n");
    var timeline = new StringBuilder("sample,mode,writer,phase,start_nanos,duration_nanos,bytes\n");
    Properties properties = connection(url, user, password, "org.postgresql.Driver", windowMiB);
    try (var telemetry =
        DatabaseTelemetryCollectors.create("postgresql", true, telemetryUrl, user, password)) {
      for (int warmup = 0; warmup < warmups; warmup++) {
        for (Mode mode : Mode.values()) {
          checkpoint("warmup:" + warmup + ":" + mode + ":begin");
          measure(properties, telemetry, fixture, mode, writers, (long) rateMiB * MIB);
          checkpoint("warmup:" + warmup + ":" + mode + ":returned");
        }
      }
      for (int sample = 1; sample <= repeats; sample++) {
        // Rotate the first mode to distribute process warm-up across modes.
        for (int order = 0; order < Mode.values().length; order++) {
          Mode mode = Mode.values()[(sample - 1 + order) % Mode.values().length];
          checkpoint("sample:" + sample + ":" + mode + ":begin");
          Trial trial =
              measure(properties, telemetry, fixture, mode, writers, (long) rateMiB * MIB);
          checkpoint("sample:" + sample + ":" + mode + ":returned");
          assertTrue(
              trial.delta().counters().getOrDefault("postgresql.wal.insert_lsn_bytes", 0L) > 0);
          DatabaseTelemetryJson.appendNdjson(
              nativeFile,
              new DatabaseTelemetryObservation(
                  Map.of(
                      "mode",
                      mode.name(),
                      "sample",
                      Integer.toString(sample),
                      "payloadMiB",
                      Integer.toString(payloadMiB),
                      "writers",
                      Integer.toString(writers),
                      "windowMiB",
                      Integer.toString(windowMiB),
                      "rttNanos",
                      Long.toString(rttNanos)),
                  trial.delta()));
          long read = 0;
          long write = 0;
          long wait = 0;
          long overlap = 0;
          long bound = 0;
          for (int writer = 0; writer < trial.publications().size(); writer++) {
            var transfer = trial.publications().get(writer).transfer();
            bound += transfer.bufferBytesUpperBound();
            for (Span span : transfer.spans()) {
              append(
                  timeline,
                  sample,
                  mode,
                  writer,
                  span.phase(),
                  span.startNanos(),
                  span.durationNanos(),
                  span.bytes());
              switch (span.phase()) {
                case "staging-read" -> read += span.durationNanos();
                case "jdbc-window" -> write += span.durationNanos();
                case "reader-wait" -> wait += span.durationNanos();
                default -> throw new AssertionError("unexpected phase " + span.phase());
              }
            }
            overlap += overlap(transfer.spans());
          }
          append(
              summary,
              sample,
              mode,
              fixture.bytes(),
              windowMiB,
              writers,
              requestedRtt,
              rttNanos,
              rateMiB,
              trial.wallNanos(),
              trial.cpuNanos(),
              trial.allocatedBytes(),
              trial.heapUpperBytes(),
              bound,
              read,
              write,
              wait,
              overlap,
              fixture.bytes(),
              fixture.bytes() * writers);
          Files.writeString(output.resolve("trials.csv"), summary);
          Files.writeString(output.resolve("timeline.csv"), timeline);
        }
      }
    }
    Files.writeString(output.resolve("trials.csv"), summary);
    Files.writeString(output.resolve("timeline.csv"), timeline);
    DatabaseTelemetryJson.writeAggregate(nativeFile, output.resolve("database-telemetry.json"));
    recordRollbacks(
        properties,
        fixture.bytes() > (long) windowMiB * MIB ? fixture : fixture(windowMiB + 1),
        windowMiB,
        repeats,
        (long) rateMiB * MIB,
        output);
  }

  private static void recordRollbacks(
      Properties properties,
      Fixture fixture,
      int windowMiB,
      int repeats,
      long readRate,
      Path output)
      throws Exception {
    var rows =
        new StringBuilder(
            "sample,mode,failure_stage,window_mib,payload_bytes,first_window_flushed_nanos,failure_detected_nanos,failure_to_return_nanos,rollback_nanos,flushed_chunks,remaining_packs,remaining_chunks\n");
    checkpoint("rollback-provider:open:begin");
    try (var provider = new HibernateSessionFactoryProvider(properties);
        var repository = HibernateRepository.create(provider.getSessionFactory(), "staging-rollback")) {
      checkpoint("rollback-provider:open:returned");
      repository.create(true);
      for (int sample = 1; sample <= repeats; sample++) {
        for (Mode mode : Mode.values()) {
          for (FailureStage stage : FailureStage.values()) {
            checkpoint("rollback:" + sample + ":" + mode + ":" + stage + ":begin");
            var result =
                StagedChunkPipelineExperiment.measureRollback(
                    provider.getSessionFactory(),
                    repository,
                    fixture.file(),
                    mode,
                    readRate,
                    windowMiB,
                    stage);
            checkpoint("rollback:" + sample + ":" + mode + ":" + stage + ":returned");
            append(
                rows,
                sample,
                mode,
                stage,
                windowMiB,
                fixture.bytes(),
                result.firstWindowFlushedNanos(),
                result.failureDetectedNanos(),
                result.failureToReturnNanos(),
                result.rollbackNanos(),
                result.flushedChunks(),
                result.remainingPacks(),
                result.remainingChunks());
            Files.writeString(output.resolve("rollback.csv"), rows);
          }
        }
      }
      checkpoint("rollback-resources:close:begin");
    } finally {
      checkpoint("rollback-resources:close:returned");
    }
  }

  private static Trial measure(
      Properties properties,
      DatabaseTelemetryCollector telemetry,
      Fixture fixture,
      Mode mode,
      int writers,
      long readRate)
      throws Exception {
    checkpoint("provider:open:begin");
    try (var provider = new HibernateSessionFactoryProvider(properties);
        var pool =
            new ThreadPoolExecutor(
                writers, writers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(writers))) {
      checkpoint("provider:open:returned");
      var factory = provider.getSessionFactory();
      var ready = new CountDownLatch(writers);
      var start = new CountDownLatch(1);
      List<Future<Publication>> pending = new ArrayList<>(writers);
      try {
        checkpoint("repository-setup:begin");
        for (int i = 0; i < writers; i++) {
          String repositoryName = "stage-pipeline-" + i;
          try (var repository = HibernateRepository.create(factory, repositoryName)) {
            repository.create(true);
          }
          pending.add(
              pool.submit(
                  () -> {
                    try (var repository = HibernateRepository.create(factory, repositoryName)) {
                      ready.countDown();
                      assertTrue(
                          start.await(30, TimeUnit.SECONDS), "publication start barrier expired");
                      return StagedChunkPipelineExperiment.publish(
                          factory, repository, fixture.file(), mode, readRate);
                    }
                  }));
        }
        List<Publication> published = new ArrayList<>(writers);
        assertTrue(ready.await(30, TimeUnit.SECONDS), "publishers did not reach barrier");
        checkpoint("repository-setup:returned");
        var heap =
            ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(poolBean -> poolBean.getType() == MemoryType.HEAP)
                .toList();
        heap.forEach(poolBean -> poolBean.resetPeakUsage());
        var system = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        checkpoint("telemetry:before:begin");
        DatabaseTelemetrySnapshot before = telemetry.capture();
        checkpoint("telemetry:before:returned");
        checkpoint("publication:begin");
        long cpuStart = system.getProcessCpuTime();
        long allocationStart = threads.getTotalThreadAllocatedBytes();
        long started = System.nanoTime();
        start.countDown();
        long deadline = started + TimeUnit.MINUTES.toNanos(15);
        for (var publication : pending) {
          published.add(
              publication.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        }
        long elapsed = System.nanoTime() - started;
        long cpu = system.getProcessCpuTime() - cpuStart;
        long allocated = threads.getTotalThreadAllocatedBytes() - allocationStart;
        long heapUpper =
            heap.stream().mapToLong(poolBean -> poolBean.getPeakUsage().getUsed()).sum();
        checkpoint("publication:returned");
        checkpoint("telemetry:after:begin");
        DatabaseTelemetryDelta delta = before.deltaTo(telemetry.capture());
        checkpoint("telemetry:after:returned");
        for (int writer = 0; writer < published.size(); writer++) {
          Publication publication = published.get(writer);
          assertTrue(publication.elapsedNanos() > 0);
          assertEquals(
              fixture.bytes(),
              publication.transfer().spans().stream().mapToLong(Span::bytes).sum());
          assertTrue(publication.transfer().retainedWindows() <= 2);
          if (mode == Mode.SYNCHRONOUS) {
            assertEquals(0L, overlap(publication.transfer().spans()));
          }
          checkpoint("reopen:" + writer + ":begin");
          try (var reopened = HibernateRepository.create(factory, "stage-pipeline-" + writer)) {
            var digest = MessageDigest.getInstance("SHA-256");
            assertEquals(
                fixture.bytes(),
                StagedChunkPipelineExperiment.digestReopened(
                    reopened, publication.description(), digest));
            assertArrayEquals(fixture.digest(), digest.digest());
          }
          checkpoint("reopen:" + writer + ":returned");
        }
        return new Trial(List.copyOf(published), elapsed, cpu, allocated, heapUpper, delta);
      } finally {
        checkpoint("resources:close:begin");
        pending.forEach(future -> future.cancel(true));
        start.countDown();
      }
    } finally {
      checkpoint("resources:close:returned");
    }
  }

  private static long overlap(List<Span> spans) {
    long total = 0;
    for (Span read : spans) {
      if (read.phase().equals("staging-read")) {
        for (Span write : spans) {
          if (write.phase().equals("jdbc-window")) {
            long start = Math.max(read.startNanos(), write.startNanos());
            long end =
                Math.min(
                    read.startNanos() + read.durationNanos(),
                    write.startNanos() + write.durationNanos());
            total += Math.max(0, end - start);
          }
        }
      }
    }
    return total;
  }

  private Fixture fixture(int payloadMiB) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    Path directory = Path.of(System.getProperty(PREFIX + ".staging_directory", scratch.toString()));
    Files.createDirectories(directory);
    Path file = Files.createTempFile(directory, "staged-pack-", ".tmp");
    file.toFile().deleteOnExit();
    long bytes = (long) payloadMiB * MIB + 257;
    Random random = new Random(189);
    byte[] block = new byte[64 * 1024];
    try (var output = new DigestOutputStream(Files.newOutputStream(file), digest)) {
      for (long left = bytes; left > 0; ) {
        random.nextBytes(block);
        int count = (int) Math.min(block.length, left);
        output.write(block, 0, count);
        left -= count;
      }
    }
    return new Fixture(file, bytes, digest.digest());
  }

  private static Properties connection(
      String url, String user, String password, String driver, int windowMiB) {
    BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
    Properties props = new Properties();
    props.setProperty("hibernate.connection.url", url);
    props.setProperty("hibernate.connection.username", user);
    props.setProperty("hibernate.connection.password", password);
    props.setProperty("hibernate.connection.driver_class", driver);
    props.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    props.setProperty("hibernate.search.enabled", "false");
    props.setProperty(HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, Integer.toString(windowMiB));
    props.setProperty(HibernateStorageSettings.JDBC_BATCH_SIZE, Integer.toString(windowMiB));
    if (driver.equals("org.postgresql.Driver")) {
      props.setProperty("hibernate.hikari.connectionInitSql", "SET statement_timeout = '60s'");
      props.setProperty("hibernate.hikari.connectionTimeout", "15000");
    }
    return props;
  }

  private static String withPostgresqlTimeouts(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "connectTimeout=10&socketTimeout=30&cancelSignalTimeout=5";
  }

  private static Calibration calibrate(String url, String user, String password) throws Exception {
    long[] samples = new long[9];
    try (var connection = DriverManager.getConnection(url, user, password);
        var query = connection.prepareStatement("SELECT 1")) {
      for (int i = -3; i < samples.length; i++) {
        long start = System.nanoTime();
        try (var result = query.executeQuery()) {
          assertTrue(result.next());
          assertEquals(1, result.getInt(1));
        }
        if (i >= 0) {
          samples[i] = System.nanoTime() - start;
        }
      }
    }
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    return new Calibration(sorted[sorted.length / 2], samples);
  }

  private static void append(StringBuilder target, Object... fields) {
    for (int i = 0; i < fields.length; i++) {
      if (i > 0) {
        target.append(',');
      }
      target.append(fields[i]);
    }
    target.append('\n');
  }

  private record Fixture(Path file, long bytes, byte[] digest) {}

  private record Calibration(long medianNanos, long[] samples) {}

  private record Trial(
      List<Publication> publications,
      long wallNanos,
      long cpuNanos,
      long allocatedBytes,
      long heapUpperBytes,
      DatabaseTelemetryDelta delta) {}
}
