/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.hibernate.SessionEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Diagnostic baseline for the synchronous staging read and JDBC write phases. */
public class SynchronousPackPublicationEvidenceTest {

  private static final String PROPERTY_PREFIX = "jgit.storage.benchmark.staging-jdbc";
  private static final String NATIVE = PROPERTY_PREFIX + ".native";
  private static final String FILE_READ_EVENT = "jdk.FileRead";
  private static final String JDBC_BATCH_EVENT = "jgit.storage.ChunkJdbcBatch";
  private static final String OBJECTS_PACKAGE =
      "io.github.carstenartur.jgit.storage.hibernate.objects.";
  private static final int MIB = 1024 * 1024;
  private static final String REPOSITORY = "synchronous-pack-phase";

  @TempDir Path scratch;

  @Test
  void observesBothSynchronousPhasesAndReopensThePack() throws Exception {
    try (DatabaseTelemetryCollector telemetry =
        DatabaseTelemetryCollectors.disabled("hsqldb", "embedded-phase-contract")) {
      measure(
          properties("jdbc:hsqldb:mem:pack-phase", "sa", "", "org.hsqldb.jdbc.JDBCDriver", 16),
          telemetry,
          payload(16));
    }
  }

  @Test
  @EnabledIfSystemProperty(named = NATIVE, matches = "true")
  void retainsPostgresqlPhaseTimelineAndNativeTelemetry() throws Exception {
    try (PostgreSQLContainer database =
        new PostgreSQLContainer("postgres:17.10-alpine")
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
      database.start();
      retainPostgresqlEvidence(
          database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }
  }

  private void retainPostgresqlEvidence(String jdbcUrl, String user, String password)
      throws Exception {
    int payloadMiB = Integer.getInteger(PROPERTY_PREFIX + ".payload_mib", 16);
    int windowMiB = Integer.getInteger(PROPERTY_PREFIX + ".window_mib", 16);
    assertTrue(List.of(16, 128, 512).contains(payloadMiB), "unsupported payload size");
    assertTrue(List.of(16, 32).contains(windowMiB), "unsupported writer window");
    Path output =
        Path.of(System.getProperty(PROPERTY_PREFIX + ".output", "target/staging-jdbc-evidence"));
    Files.createDirectories(output);
    Path ndjson = output.resolve("database-telemetry.ndjson");
    Path json = output.resolve("database-telemetry.json");
    Path csv = output.resolve("phase-timeline.csv");
    Files.deleteIfExists(ndjson);
    Files.deleteIfExists(json);
    Files.deleteIfExists(csv);
    StringBuilder timeline =
        new StringBuilder("sample,phase,start_nanos,duration_nanos,bytes_read\n");
    byte[] payload = payload(payloadMiB);
    Properties properties = properties(jdbcUrl, user, password, "org.postgresql.Driver", windowMiB);
    try (DatabaseTelemetryCollector telemetry =
        DatabaseTelemetryCollectors.create("postgresql", true, jdbcUrl, user, password)) {
      for (int sample = 1; sample <= 3; sample++) {
        Measurement measurement = measure(properties, telemetry, payload);
        assertTrue(
            measurement.nativeDelta().counters().getOrDefault("postgresql.wal.insert_lsn_bytes", 0L)
                > 0,
            "the native observation must include a positive WAL insert-position delta");
        DatabaseTelemetryJson.appendNdjson(
            ndjson,
            new DatabaseTelemetryObservation(
                Map.of(
                    "operation", "synchronous-pack-publication",
                    "sample", Integer.toString(sample),
                    "payloadMiB", Integer.toString(payloadMiB),
                    "chunkWindowMiB", Integer.toString(windowMiB),
                    "chunkWriter", "auto",
                    "temporaryFileBytesRead", Long.toString(measurement.temporaryBytesRead()),
                    "elapsedNanos", Long.toString(measurement.elapsedNanos())),
                measurement.nativeDelta()));
        Instant origin = measurement.spans().getFirst().start();
        for (Span span : measurement.spans()) {
          timeline
              .append(sample)
              .append(',')
              .append(span.phase())
              .append(',')
              .append(Duration.between(origin, span.start()).toNanos())
              .append(',')
              .append(span.duration().toNanos())
              .append(',')
              .append(span.bytesRead())
              .append('\n');
        }
      }
    }
    Files.writeString(csv, timeline);
    DatabaseTelemetryJson.writeAggregate(ndjson, json);
  }

  private Measurement measure(
      Properties properties, DatabaseTelemetryCollector telemetry, byte[] payload)
      throws Exception {
    Path trace = scratch.resolve("publication.jfr");
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties)) {
      ObjectId objectId;
      long elapsed;
      long temporaryBytesRead;
      DatabaseTelemetryDelta delta;
      try (HibernateRepository repository =
              HibernateRepository.create(provider.getSessionFactory(), REPOSITORY);
          Recording recording = new Recording()) {
        repository.create(true);
        long bytesBefore = repository.getStorageByteMetrics().temporaryFileBytesRead();
        recording.enable(FILE_READ_EVENT).withThreshold(Duration.ZERO).withStackTrace();
        recording.enable(JdbcBatchEvent.class).withThreshold(Duration.ZERO).withStackTrace();
        recording.start();
        DatabaseTelemetrySnapshot before = telemetry.capture();
        long startedAt = System.nanoTime();
        try (ObjectInserter inserter = repository.newObjectInserter()) {
          objectId = inserter.insert(Constants.OBJ_BLOB, payload);
          inserter.flush();
        }
        elapsed = System.nanoTime() - startedAt;
        delta = before.deltaTo(telemetry.capture());
        recording.stop();
        recording.dump(trace);
        temporaryBytesRead =
            repository.getStorageByteMetrics().temporaryFileBytesRead() - bytesBefore;
      }

      List<Span> spans = readSpans(trace);
      verifySpans(spans, payload.length, temporaryBytesRead);
      verifyReopenedPayload(provider, objectId, payload);
      return new Measurement(elapsed, temporaryBytesRead, spans, delta);
    } finally {
      Files.deleteIfExists(trace);
    }
  }

  private static List<Span> readSpans(Path trace) throws IOException {
    long publisherThread = Thread.currentThread().threadId();
    List<Span> spans = new ArrayList<>();
    try (RecordingFile events = new RecordingFile(trace)) {
      while (events.hasMoreEvents()) {
        RecordedEvent event = events.readEvent();
        if (event.getThread() == null || event.getThread().getJavaThreadId() != publisherThread) {
          continue;
        }
        String name = event.getEventType().getName();
        if (JDBC_BATCH_EVENT.equals(name)
            && stackContains(event, "HibernatePackChunkWriter", "flushPending")) {
          spans.add(new Span("jdbc-batch", event.getStartTime(), event.getDuration(), 0));
        } else if (FILE_READ_EVENT.equals(name) && isChunkStagingRead(event)) {
          spans.add(
              new Span(
                  "staging-read",
                  event.getStartTime(),
                  event.getDuration(),
                  event.getLong("bytesRead")));
        }
      }
    }
    spans.sort(Comparator.comparing(Span::start));
    return spans;
  }

  private static boolean isChunkStagingRead(RecordedEvent event) {
    String path = event.getString("path");
    return path != null
        && Path.of(path).getFileName().toString().startsWith("jgit-storage-pack-")
        && path.endsWith(".tmp")
        && event.getLong("bytesRead") > 0
        && stackContains(event, "StagedPackExtensionStore", "persistChunks");
  }

  private static boolean stackContains(RecordedEvent event, String type, String method) {
    return event.getStackTrace() != null
        && event.getStackTrace().getFrames().stream()
            .anyMatch(
                frame ->
                    (OBJECTS_PACKAGE + type).equals(frame.getMethod().getType().getName())
                        && method.equals(frame.getMethod().getName()));
  }

  private static void verifySpans(List<Span> spans, long payloadBytes, long temporaryBytesRead) {
    long tracedBytes = spans.stream().mapToLong(Span::bytesRead).sum();
    assertTrue(tracedBytes >= payloadBytes, "trace must cover the incompressible staged pack");
    assertTrue(
        tracedBytes <= temporaryBytesRead,
        () -> "traced " + tracedBytes + " of " + temporaryBytesRead + " temporary bytes read");
    assertTrue(
        spans.stream().anyMatch(span -> "jdbc-batch".equals(span.phase())),
        "trace must include JDBC batch execution");
    for (int index = 1; index < spans.size(); index++) {
      Span previous = spans.get(index - 1);
      assertFalse(
          spans.get(index).start().isBefore(previous.start().plus(previous.duration())),
          "the reference writer must not overlap staging reads and JDBC batches");
    }
  }

  private static void verifyReopenedPayload(
      HibernateSessionFactoryProvider provider, ObjectId objectId, byte[] payload)
      throws Exception {
    // JGit shares the DFS cache across repository handles and caches pack blocks on insert.
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (HibernateRepository reopened =
        HibernateRepository.create(provider.getSessionFactory(), REPOSITORY)) {
      long databaseBytesBefore = reopened.getStorageByteMetrics().databasePayloadBytesRead();
      var loader = reopened.open(objectId);
      assertEquals(Constants.OBJ_BLOB, loader.getType());
      try (InputStream stream = new DigestInputStream(loader.openStream(), digest)) {
        assertEquals(payload.length, stream.transferTo(OutputStream.nullOutputStream()));
      }
      long databaseBytesRead =
          reopened.getStorageByteMetrics().databasePayloadBytesRead() - databaseBytesBefore;
      assertTrue(
          databaseBytesRead >= payload.length,
          () ->
              "reopen read "
                  + databaseBytesRead
                  + " database payload bytes, expected at least "
                  + payload.length);
    }
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), digest.digest());
  }

  private static Properties properties(
      String jdbcUrl, String user, String password, String driver, int windowMiB) {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.url", jdbcUrl);
    properties.setProperty("hibernate.connection.username", user);
    properties.setProperty("hibernate.connection.password", password);
    properties.setProperty("hibernate.connection.driver_class", driver);
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    properties.setProperty("hibernate.search.enabled", "false");
    properties.setProperty("hibernate.session.events.auto", BatchEventListener.class.getName());
    properties.setProperty(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    properties.setProperty(
        HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, Integer.toString(windowMiB));
    properties.setProperty(HibernateStorageSettings.JDBC_BATCH_SIZE, Integer.toString(windowMiB));
    return properties;
  }

  private static byte[] payload(int payloadMiB) {
    byte[] bytes = new byte[Math.addExact(Math.multiplyExact(payloadMiB, MIB), 257)];
    new Random(0x189L).nextBytes(bytes);
    return bytes;
  }

  private record Span(String phase, Instant start, Duration duration, long bytesRead) {}

  private record Measurement(
      long elapsedNanos,
      long temporaryBytesRead,
      List<Span> spans,
      DatabaseTelemetryDelta nativeDelta) {}

  @Name(JDBC_BATCH_EVENT)
  static final class JdbcBatchEvent extends Event {}

  public static final class BatchEventListener implements SessionEventListener {
    private JdbcBatchEvent batch;

    @Override
    public void jdbcExecuteBatchStart() {
      batch = new JdbcBatchEvent();
      batch.begin();
    }

    @Override
    public void jdbcExecuteBatchEnd() {
      JdbcBatchEvent completed = batch;
      batch = null;
      if (completed != null) {
        completed.end();
        completed.commit();
      }
    }
  }
}
