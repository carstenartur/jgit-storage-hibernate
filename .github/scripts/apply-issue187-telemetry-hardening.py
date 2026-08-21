#!/usr/bin/env python3
"""Harden issue #187 telemetry determinism and short-window semantics."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


snapshot_path = Path(
    "jgit-storage-hibernate-benchmarks/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "DatabaseTelemetrySnapshot.java"
)
snapshot = snapshot_path.read_text(encoding="utf-8")
snapshot = replace_once(
    snapshot,
    "import java.time.Instant;\nimport java.util.Map;\n",
    "import java.time.Instant;\nimport java.util.Collections;\nimport java.util.Map;\n",
    "snapshot Collections import",
)
snapshot = replace_once(
    snapshot,
    "    Instant capturedAt,\n    String serverVersion,\n",
    "    Instant captureStartedAt,\n    Instant captureCompletedAt,\n    String serverVersion,\n",
    "snapshot capture timestamps",
)
snapshot = replace_once(
    snapshot,
    '''    capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    serverVersion = serverVersion == null ? "unknown" : serverVersion;
''',
    '''    captureStartedAt = Objects.requireNonNull(captureStartedAt, "captureStartedAt");
    captureCompletedAt =
        Objects.requireNonNull(captureCompletedAt, "captureCompletedAt");
    if (captureCompletedAt.isBefore(captureStartedAt)) {
      throw new IllegalArgumentException(
          "captureCompletedAt must not precede captureStartedAt");
    }
    serverVersion = serverVersion == null ? "unknown" : serverVersion;
''',
    "snapshot timestamp validation",
)
snapshot = replace_once(
    snapshot,
    '''  static DatabaseTelemetrySnapshot disabled(String backend, String reason) {
    return new DatabaseTelemetrySnapshot(
        backend,
        false,
        Instant.now(),
        "unknown",
''',
    '''  static DatabaseTelemetrySnapshot disabled(String backend, String reason) {
    Instant capturedAt = Instant.now();
    return new DatabaseTelemetrySnapshot(
        backend,
        false,
        capturedAt,
        capturedAt,
        "unknown",
''',
    "disabled snapshot timestamps",
)
snapshot = replace_once(
    snapshot,
    '''        capturedAt,
        after.capturedAt,
        after.serverVersion,
''',
    '''        captureCompletedAt,
        after.captureStartedAt,
        after.serverVersion,
''',
    "delta window boundaries",
)
copy_count = snapshot.count("return Map.copyOf(result);")
if copy_count != 2:
    raise SystemExit(f"Expected two validated snapshot map copies, found {copy_count}")
snapshot = snapshot.replace(
    "return Map.copyOf(result);",
    "return Collections.unmodifiableMap(result);",
)
copy_tree_count = snapshot.count("Map.copyOf(new TreeMap<>(")
if copy_tree_count != 5:
    raise SystemExit(
        f"Expected five delta/coordinate map copies, found {copy_tree_count}"
    )
snapshot = snapshot.replace(
    "Map.copyOf(new TreeMap<>(",
    "Collections.unmodifiableMap(new TreeMap<>(",
)
snapshot_path.write_text(snapshot, encoding="utf-8")


collectors_path = Path(
    "jgit-storage-hibernate-benchmarks/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "DatabaseTelemetryCollectors.java"
)
collectors = collectors_path.read_text(encoding="utf-8")
wal_anchor = '''      queryOne(
          connection,
          snapshot,
          "postgresql.pg_stat_wal",
'''
wal_position_query = '''      queryOne(
          connection,
          snapshot,
          "postgresql.wal_positions",
          """
          SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(), '0/0')::bigint
                   AS insert_lsn_bytes,
                 pg_wal_lsn_diff(pg_current_wal_flush_lsn(), '0/0')::bigint
                   AS flush_lsn_bytes
          """,
          (result, value) -> {
            value.counter(
                "postgresql.wal.insert_lsn_bytes",
                result.getLong("insert_lsn_bytes"));
            value.counter(
                "postgresql.wal.flush_lsn_bytes",
                result.getLong("flush_lsn_bytes"));
          });

'''
collectors = replace_once(
    collectors,
    wal_anchor,
    wal_position_query + wal_anchor,
    "PostgreSQL WAL-position query",
)
collectors = replace_once(
    collectors,
    '''  private static final class SnapshotBuilder {
    private final String backend;
    private final Instant capturedAt = Instant.now();
''',
    '''  private static final class SnapshotBuilder {
    private final String backend;
    private final Instant captureStartedAt = Instant.now();
''',
    "snapshot builder start timestamp",
)
collectors = replace_once(
    collectors,
    '''    private SnapshotBuilder(String backend) {
      this.backend = backend;
    }
''',
    '''    private SnapshotBuilder(String backend) {
      this.backend = backend;
      metadata.put(
          "telemetry.window.boundary",
          "pre-capture-complete-to-post-capture-start");
      metadata.put("telemetry.counter.scope", "cumulative-observational");
    }
''',
    "snapshot interpretation metadata",
)
collectors = replace_once(
    collectors,
    '''          backend,
          true,
          capturedAt,
          serverVersion,
''',
    '''          backend,
          true,
          captureStartedAt,
          Instant.now(),
          serverVersion,
''',
    "snapshot builder completion timestamp",
)
collectors_path.write_text(collectors, encoding="utf-8")


test_path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "DatabaseTelemetrySnapshotTest.java"
)
tests = test_path.read_text(encoding="utf-8")
tests = replace_once(
    tests,
    '''  @Test
  void missingCounterOnEitherSideRemainsExplicit() {
''',
    '''  @Test
  void usesCompletedPreCaptureAndStartedPostCaptureAsWindowBoundaries() {
    DatabaseTelemetrySnapshot before =
        new DatabaseTelemetrySnapshot(
            "postgresql",
            true,
            Instant.parse("2026-08-21T10:00:00Z"),
            Instant.parse("2026-08-21T10:00:01Z"),
            "17.10",
            Map.of("wal.bytes", 1L),
            Map.of(),
            Map.of(),
            Map.of());
    DatabaseTelemetrySnapshot after =
        new DatabaseTelemetrySnapshot(
            "postgresql",
            true,
            Instant.parse("2026-08-21T10:00:03Z"),
            Instant.parse("2026-08-21T10:00:04Z"),
            "17.10",
            Map.of("wal.bytes", 2L),
            Map.of(),
            Map.of(),
            Map.of());

    DatabaseTelemetryDelta delta = before.deltaTo(after);

    assertEquals(Instant.parse("2026-08-21T10:00:01Z"), delta.startedAt());
    assertEquals(Instant.parse("2026-08-21T10:00:03Z"), delta.completedAt());
  }

  @Test
  void serializesCoordinateAndMetricKeysInStableSortedOrder() {
    DatabaseTelemetrySnapshot before =
        snapshot(
            Instant.parse("2026-08-21T10:00:00Z"),
            Map.of("z-counter", 1L, "a-counter", 1L),
            Map.of("z-gauge", 2L, "a-gauge", 2L));
    DatabaseTelemetrySnapshot after =
        snapshot(
            Instant.parse("2026-08-21T10:00:01Z"),
            Map.of("z-counter", 2L, "a-counter", 3L),
            Map.of("z-gauge", 3L, "a-gauge", 4L));
    DatabaseTelemetryObservation observation =
        new DatabaseTelemetryObservation(
            Map.of("z-coordinate", "2", "a-coordinate", "1"),
            before.deltaTo(after));

    String value = DatabaseTelemetryJson.observationJson(observation);

    assertTrue(
        value.contains(
            "\\\"coordinate\\\":{\\\"a-coordinate\\\":\\\"1\\\","
                + "\\\"z-coordinate\\\":\\\"2\\\"}"));
    assertTrue(
        value.contains(
            "\\\"counters\\\":{\\\"a-counter\\\":2,\\\"z-counter\\\":1}"));
    assertTrue(
        value.contains(
            "\\\"gauges\\\":{\\\"a-gauge\\\":4,\\\"z-gauge\\\":3}"));
  }

  @Test
  void missingCounterOnEitherSideRemainsExplicit() {
''',
    "snapshot boundary and ordering tests",
)
tests = replace_once(
    tests,
    '''        true,
        capturedAt,
        "17.10",
''',
    '''        true,
        capturedAt,
        capturedAt,
        "17.10",
''',
    "snapshot test helper timestamps",
)
test_path.write_text(tests, encoding="utf-8")


integration_path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "DatabaseNativeTelemetryIntegrationTest.java"
)
integration = integration_path.read_text(encoding="utf-8")
integration = replace_once(
    integration,
    '''          "postgresql.wal.bytes",
          "postgresql.database.blocks_hit");
''',
    '''          "postgresql.wal.insert_lsn_bytes",
          "postgresql.wal.bytes");
''',
    "PostgreSQL immediate WAL integration counter",
)
integration = replace_once(
    integration,
    '''    assertTrue(after.counters().containsKey(requiredCounter), after.unsupported().toString());
    assertTrue(
        after.counters().containsKey(secondRequiredCounter),
        after.unsupported().toString());
''',
    '''    assertTrue(after.counters().containsKey(requiredCounter), after.unsupported().toString());
    assertTrue(
        after.counters().containsKey(secondRequiredCounter),
        after.unsupported().toString());
    assertTrue(delta.counters().containsKey(requiredCounter), delta.unsupported().toString());
    assertTrue(
        delta.counters().get(requiredCounter) > 0L,
        () -> "Expected positive " + requiredCounter + " delta but got " + delta.counters());
''',
    "positive native delta integration assertion",
)
integration_path.write_text(integration, encoding="utf-8")


docs_path = Path("docs/operations/database-native-telemetry.md")
docs = docs_path.read_text(encoding="utf-8")
docs = replace_once(
    docs,
    '''- `pg_stat_wal`: WAL records, full-page images, bytes, buffer-full events, writes, syncs and available write/sync timing;
''',
    '''- current WAL insert and flush positions converted to monotonic byte counters for immediate short-window deltas;
- `pg_stat_wal`: WAL records, full-page images, bytes, buffer-full events, writes, syncs and available write/sync timing;
''',
    "PostgreSQL WAL-position documentation",
)
docs = replace_once(
    docs,
    '''The artifact also records whether `track_io_timing` and `track_wal_io_timing` are enabled. A zero timing delta is not interpreted as proof of zero physical I/O when the relevant timing setting is disabled.
''',
    '''The artifact also records whether `track_io_timing` and `track_wal_io_timing` are enabled. A zero timing delta is not interpreted as proof of zero physical I/O when the relevant timing setting is disabled.

`pg_stat_wal`, `pg_stat_database` and `pg_stat_io` are cumulative statistics whose publication can lag a very short invocation. The WAL insert/flush-position deltas are therefore the primary immediate PostgreSQL byte signal; the statistics-view deltas remain complementary evidence. All cumulative views are observational and can include background work plus the collector's own read-only statements. The JSON metadata records this scope explicitly.

The serialized `startedAt`/`completedAt` interval begins only after the pre-invocation snapshot has completed and ends immediately before the post-invocation snapshot starts. Snapshot-query duration is therefore not presented as benchmark-window duration.
''',
    "short-window interpretation documentation",
)
docs_path.write_text(docs, encoding="utf-8")
