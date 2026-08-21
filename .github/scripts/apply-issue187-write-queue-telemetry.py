#!/usr/bin/env python3
"""Integrate database-native telemetry with the concurrent write-queue benchmark."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


benchmark_path = Path(
    "jgit-storage-hibernate-benchmarks/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "DurableWriteQueueBenchmark.java"
)
benchmark = benchmark_path.read_text(encoding="utf-8")
benchmark = replace_once(
    benchmark,
    '''import java.io.IOException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
''',
    '''import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
''',
    "write-queue telemetry imports",
)
benchmark = replace_once(
    benchmark,
    '''import org.openjdk.jmh.infra.ThreadParams;
''',
    '''import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.runner.IterationType;
''',
    "write-queue JMH telemetry imports",
)
benchmark = replace_once(
    benchmark,
    '''  private String sharedRepositoryName;
  private String[] isolatedRepositoryNames;
  private HibernateSessionFactoryProvider schemaProvider;
  private DurableStripedWriteQueue queue;
''',
    '''  private final AtomicInteger measurementIteration = new AtomicInteger();
  private String sharedRepositoryName;
  private String[] isolatedRepositoryNames;
  private HibernateSessionFactoryProvider schemaProvider;
  private DurableStripedWriteQueue queue;
  private DatabaseTelemetryCollector telemetryCollector;
  private DatabaseTelemetrySnapshot iterationTelemetryBefore;
''',
    "write-queue telemetry state",
)
benchmark = replace_once(
    benchmark,
    '''  public void setupTrial() throws Exception {
    String trialId = Long.toHexString(System.nanoTime());
''',
    '''  public void setupTrial() throws Exception {
    suppressConnectionMetadataLogging();
    String trialId = Long.toHexString(System.nanoTime());
''',
    "write-queue log suppression",
)
benchmark = replace_once(
    benchmark,
    '''    if (stripes > 0) {
      queue = new DurableStripedWriteQueue(DurableStripedWriteQueue.Limits.benchmarkDefaults(stripes));
    }
  }

  @TearDown(Level.Trial)
''',
    '''    if (stripes > 0) {
      queue = new DurableStripedWriteQueue(DurableStripedWriteQueue.Limits.benchmarkDefaults(stripes));
    }
    telemetryCollector = databaseTelemetryCollector();
  }

  @Setup(Level.Iteration)
  public void setupIteration(IterationParams iterationParams) {
    iterationTelemetryBefore = null;
    if (telemetryCollector.enabled()
        && iterationParams.getType() == IterationType.MEASUREMENT) {
      iterationTelemetryBefore = telemetryCollector.capture();
    }
  }

  @TearDown(Level.Iteration)
  public void tearDownIteration(
      BenchmarkParams benchmarkParams, IterationParams iterationParams) throws IOException {
    try {
      if (iterationTelemetryBefore != null
          && iterationParams.getType() == IterationType.MEASUREMENT) {
        DatabaseTelemetrySnapshot after = telemetryCollector.capture();
        DatabaseTelemetryJson.appendNdjson(
            requiredTelemetryOutput(),
            new DatabaseTelemetryObservation(
                telemetryCoordinate(
                    benchmarkParams, measurementIteration.incrementAndGet()),
                iterationTelemetryBefore.deltaTo(after)));
      }
    } finally {
      iterationTelemetryBefore = null;
    }
  }

  @TearDown(Level.Trial)
''',
    "write-queue measurement-iteration capture",
)
benchmark = replace_once(
    benchmark,
    '''  public void tearDownTrial() {
    if (queue != null) {
''',
    '''  public void tearDownTrial() {
    iterationTelemetryBefore = null;
    if (telemetryCollector != null) {
      telemetryCollector.close();
      telemetryCollector = null;
    }
    if (queue != null) {
''',
    "write-queue telemetry cleanup",
)
benchmark = replace_once(
    benchmark,
    '''  private void createRepository(String repositoryName) throws IOException {
''',
    '''  private DatabaseTelemetryCollector databaseTelemetryCollector() {
    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    if (!enabled) {
      return DatabaseTelemetryCollectors.disabled(
          "postgresql", "disabled-by-configuration");
    }
    return DatabaseTelemetryCollectors.create(
        "postgresql",
        true,
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
  }

  private Path requiredTelemetryOutput() {
    String value = System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing benchmark system property "
              + DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    }
    return Path.of(value);
  }

  private Map<String, String> telemetryCoordinate(
      BenchmarkParams benchmarkParams, int iteration) {
    String benchmark = benchmarkParams.getBenchmark();
    String benchmarkMethod =
        benchmark.substring(benchmark.lastIndexOf('.') + 1);
    String repositoryScope =
        switch (benchmarkMethod) {
          case "publishToSameRepository" -> "shared";
          case "publishToDifferentRepositories" -> "isolated";
          default ->
              throw new IllegalArgumentException(
                  "Unsupported write-queue benchmark method " + benchmarkMethod);
        };
    return Map.of(
        "backend", backend,
        "benchmarkMethod", benchmarkMethod,
        "databaseBackend", "postgresql",
        "executionMode", executionMode,
        "measurementIteration", Integer.toString(iteration),
        "payloadKiB", Integer.toString(payloadKiB),
        "poolSize", "2",
        "repositoryScope", repositoryScope,
        "stripes", Integer.toString(stripes(executionMode)),
        "threads", Integer.toString(benchmarkParams.getThreads()));
  }

  private void createRepository(String repositoryName) throws IOException {
''',
    "write-queue telemetry helpers",
)
benchmark = replace_once(
    benchmark,
    '''  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }
''',
    '''  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String connectionPropertiesFile =
        System.getProperty(
            PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY);
    if (connectionPropertiesFile != null && !connectionPropertiesFile.isBlank()) {
      Properties connectionProperties = new Properties();
      try (InputStream input =
          Files.newInputStream(Path.of(connectionPropertiesFile))) {
        connectionProperties.load(input);
      } catch (IOException failure) {
        throw new IllegalStateException(
            "Cannot read temporary benchmark connection properties", failure);
      }
      value = connectionProperties.getProperty(name);
    }
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }

  private static void suppressConnectionMetadataLogging() {
    java.util.logging.Level warning = java.util.logging.Level.WARNING;
    Logger.getLogger("").setLevel(warning);
    Logger.getLogger("org.hibernate").setLevel(warning);
    Logger.getLogger("org.hibernate.orm.connections.pooling").setLevel(warning);
    Logger.getLogger(
            "org.hibernate.engine.jdbc.env.internal.JdbcEnvironmentInitiator")
        .setLevel(warning);
  }
''',
    "write-queue temporary connection properties",
)
benchmark_path.write_text(benchmark, encoding="utf-8")


runner_path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PerformanceInvestigationsBenchmarkIT.java"
)
runner = runner_path.read_text(encoding="utf-8")
runner = replace_once(
    runner,
    '''import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
''',
    '''import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
''',
    "investigation telemetry imports",
)
runner = replace_once(
    runner,
    '''    OptionsBuilder builder = baseOptions(resultFile, outputFile, full);
    builder.jvmArgsAppend(
        "-Xms1g",
        full ? "-Xmx3g" : "-Xmx1536m",
        RepositoryBackendBenchmarkIT.systemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY, POSTGRESQL.getJdbcUrl()),
        RepositoryBackendBenchmarkIT.systemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY, POSTGRESQL.getUsername()),
        RepositoryBackendBenchmarkIT.systemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
            POSTGRESQL.getPassword()));

    configure(builder, investigation, full, deployment, threads);
    Collection<RunResult> results = new Runner(builder.build()).run();

    assertFalse(results.isEmpty(), "Selected investigation produced no JMH results");
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.size(resultFile) > 2, "JMH JSON result is empty");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
  }
''',
    '''    OptionsBuilder builder = baseOptions(resultFile, outputFile, full);
    boolean writeQueue = "write-queue".equals(investigation);
    boolean telemetryEnabled =
        writeQueue
            && Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    Path telemetryNdjson =
        resultFile.resolveSibling("write-queue-database-telemetry.ndjson");
    Path telemetryJson =
        resultFile.resolveSibling("write-queue-database-telemetry.json");
    Path connectionPropertiesFile = null;
    List<String> jvmArguments = new ArrayList<>();
    jvmArguments.add("-Xms1g");
    jvmArguments.add(full ? "-Xmx3g" : "-Xmx1536m");
    if (writeQueue) {
      connectionPropertiesFile = writeConnectionProperties();
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
              connectionPropertiesFile.toString()));
    } else {
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
              POSTGRESQL.getJdbcUrl()));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
              POSTGRESQL.getUsername()));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
              POSTGRESQL.getPassword()));
    }
    if (telemetryEnabled) {
      Files.deleteIfExists(telemetryNdjson);
      Files.deleteIfExists(telemetryJson);
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              DatabaseTelemetryCollectors.ENABLED_PROPERTY, "true"));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              DatabaseTelemetryCollectors.OUTPUT_PROPERTY,
              telemetryNdjson.toString()));
    }
    builder.jvmArgsAppend(jvmArguments.toArray(String[]::new));

    configure(builder, investigation, full, deployment, threads);
    Collection<RunResult> results;
    try {
      results = new Runner(builder.build()).run();
    } finally {
      if (connectionPropertiesFile != null) {
        Files.deleteIfExists(connectionPropertiesFile);
      }
    }

    if (telemetryEnabled) {
      DatabaseTelemetryJson.writeAggregate(telemetryNdjson, telemetryJson);
      assertTrue(
          Files.isRegularFile(telemetryJson),
          "Write-queue telemetry JSON was not written");
      assertTrue(
          Files.size(telemetryJson) > 32,
          "Write-queue telemetry JSON is empty");
    }
    assertFalse(results.isEmpty(), "Selected investigation produced no JMH results");
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.size(resultFile) > 2, "JMH JSON result is empty");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
    if (writeQueue) {
      assertCredentialFreeEvidence(resultFile.getParent());
    }
  }

  private static Path writeConnectionProperties() throws IOException {
    Path target =
        Files.createTempFile(
            "jgit-storage-benchmark-connection-", ".properties");
    try {
      Files.setPosixFilePermissions(
          target, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // Supported CI performance runners use POSIX filesystems.
    }
    Properties properties = new Properties();
    Map.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
            POSTGRESQL.getJdbcUrl(),
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
            POSTGRESQL.getUsername(),
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
            POSTGRESQL.getPassword())
        .forEach(properties::setProperty);
    try (OutputStream output = Files.newOutputStream(target)) {
      properties.store(output, "ephemeral benchmark connection properties");
    } catch (IOException failure) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    return target;
  }

  private static void assertCredentialFreeEvidence(Path root) throws IOException {
    List<String> forbidden =
        List.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            "jdbc:postgresql://",
            "Database JDBC URL",
            "Default catalog/schema",
            POSTGRESQL.getPassword());
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content =
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        for (String token : forbidden) {
          assertFalse(
              content.contains(token),
              () -> "Retained evidence " + file + " contains " + token);
        }
      }
    }
  }
''',
    "investigation write-queue telemetry runner",
)
runner_path.write_text(runner, encoding="utf-8")


workflow_path = Path(".github/workflows/performance-investigations.yml")
workflow = workflow_path.read_text(encoding="utf-8")
workflow = replace_once(
    workflow,
    '''          profile="${{ github.event_name == 'workflow_dispatch' && inputs.profile || (github.event_name == 'schedule' && 'full' || 'smoke') }}"
          mvn -B -pl jgit-storage-hibernate-benchmarks verify \\
            -Pperformance-investigations \\
            -Djgit.storage.benchmark.investigation="${{ matrix.investigation }}" \\
            -Djgit.storage.benchmark.investigation.profile="$profile" \\
            -Dbenchmark.resultFile="$GITHUB_WORKSPACE/target/investigations/${{ matrix.investigation }}/jmh-result.json" 2>&1 \\
            | tee "target/investigations/${{ matrix.investigation }}/maven-benchmark.log"

      - name: Derive repository-aging policy evidence
''',
    '''          profile="${{ github.event_name == 'workflow_dispatch' && inputs.profile || (github.event_name == 'schedule' && 'full' || 'smoke') }}"
          telemetry_args=()
          if [ "${{ matrix.investigation }}" = 'write-queue' ]; then
            telemetry_args+=(
              '-Djgit.storage.benchmark.database-telemetry.enabled=true'
            )
          fi
          mvn -B -pl jgit-storage-hibernate-benchmarks verify \\
            -Pperformance-investigations \\
            -Djgit.storage.benchmark.investigation="${{ matrix.investigation }}" \\
            -Djgit.storage.benchmark.investigation.profile="$profile" \\
            "${telemetry_args[@]}" \\
            -Dbenchmark.resultFile="$GITHUB_WORKSPACE/target/investigations/${{ matrix.investigation }}/jmh-result.json" 2>&1 \\
            | tee "target/investigations/${{ matrix.investigation }}/maven-benchmark.log"

      - name: Validate write-queue native telemetry
        if: matrix.investigation == 'write-queue'
        shell: bash
        run: |
          python3 - <<'PY'
          import collections
          import datetime
          import json
          from pathlib import Path

          root = Path('target/investigations/write-queue')
          telemetry_path = root / 'write-queue-database-telemetry.json'
          jmh_path = root / 'jmh-result.json'
          telemetry_text = telemetry_path.read_text(encoding='utf-8')
          if 'NaN' in telemetry_text or 'Infinity' in telemetry_text:
              raise SystemExit('Write-queue telemetry is not strict JSON')
          observations = json.loads(telemetry_text)['observations']
          jmh = json.loads(jmh_path.read_text(encoding='utf-8'))
          profile = '${{ github.event_name == 'workflow_dispatch' && inputs.profile || (github.event_name == 'schedule' && 'full' || 'smoke') }}'
          iterations = 3 if profile == 'full' else 1
          expected = 96 if profile == 'full' else 16
          if len(observations) != expected:
              raise SystemExit(
                  f'Expected {expected} write-queue observations, found {len(observations)}'
              )

          def jmh_coordinate(item):
              method = item['benchmark'].rsplit('.', 1)[-1]
              params = item['params']
              scope = (
                  'shared'
                  if method == 'publishToSameRepository'
                  else 'isolated'
              )
              stripes = {
                  'direct': '0',
                  'queue-1': '1',
                  'queue-4': '4',
                  'queue-8': '8',
              }[params['executionMode']]
              return (
                  method,
                  params['backend'],
                  params['executionMode'],
                  params['payloadKiB'],
                  str(item['threads']),
                  scope,
                  stripes,
                  '2',
              )

          def telemetry_coordinate(item):
              coordinate = item['coordinate']
              return (
                  coordinate['benchmarkMethod'],
                  coordinate['backend'],
                  coordinate['executionMode'],
                  coordinate['payloadKiB'],
                  coordinate['threads'],
                  coordinate['repositoryScope'],
                  coordinate['stripes'],
                  coordinate['poolSize'],
              )

          jmh_coordinates = {jmh_coordinate(item) for item in jmh}
          telemetry_counts = collections.Counter(
              telemetry_coordinate(item) for item in observations
          )
          if set(telemetry_counts) != jmh_coordinates:
              raise SystemExit(
                  'JMH and native telemetry coordinate sets differ: '
                  f'{jmh_coordinates ^ set(telemetry_counts)}'
              )
          if any(count != iterations for count in telemetry_counts.values()):
              raise SystemExit(
                  f'Unexpected per-coordinate iteration counts: {telemetry_counts}'
              )

          iteration_numbers = collections.defaultdict(set)
          for item in observations:
              coordinate = item['coordinate']
              if item['backend'] != 'postgresql':
                  raise SystemExit(f'Unexpected telemetry backend: {item["backend"]}')
              if coordinate['databaseBackend'] != 'postgresql':
                  raise SystemExit(f'Unexpected database coordinate: {coordinate}')
              base = telemetry_coordinate(item)
              iteration_numbers[base].add(int(coordinate['measurementIteration']))
              for field in ('coordinate', 'counters', 'gauges', 'metadata', 'unsupported'):
                  keys = list(item[field])
                  if keys != sorted(keys):
                      raise SystemExit(
                          f'{field} keys are not deterministic for {coordinate}: {keys}'
                      )
              wal = item['counters'].get('postgresql.wal.insert_lsn_bytes')
              if wal is None or wal <= 0:
                  raise SystemExit(
                      f'Expected positive immediate WAL delta for {coordinate}: {wal}'
                  )
              start = datetime.datetime.fromisoformat(
                  item['startedAt'].replace('Z', '+00:00')
              )
              end = datetime.datetime.fromisoformat(
                  item['completedAt'].replace('Z', '+00:00')
              )
              if end < start:
                  raise SystemExit(f'Negative telemetry window for {coordinate}')
          expected_iterations = set(range(1, iterations + 1))
          if any(values != expected_iterations for values in iteration_numbers.values()):
              raise SystemExit(
                  f'Measurement iteration numbering is incomplete: {iteration_numbers}'
              )

          forbidden = (
              'jgit.storage.benchmark.postgresql.url=',
              'jgit.storage.benchmark.postgresql.user=',
              'jgit.storage.benchmark.postgresql.password=',
              'jdbc:postgresql://',
              'Database JDBC URL',
              'Default catalog/schema',
              'benchmark-password',
          )
          scan_roots = [
              root,
              Path('jgit-storage-hibernate-benchmarks/target/failsafe-reports'),
          ]
          for scan_root in scan_roots:
              if not scan_root.exists():
                  continue
              for path in scan_root.rglob('*'):
                  if not path.is_file():
                      continue
                  content = path.read_bytes().decode('utf-8', errors='replace')
                  for token in forbidden:
                      if token in content:
                          raise SystemExit(f'Sensitive token {token!r} found in {path}')
          leftovers = list(
              Path('/tmp').glob('jgit-storage-benchmark-connection-*.properties')
          )
          if leftovers:
              raise SystemExit(f'Temporary credential files remain: {leftovers}')
          print(
              f'Validated {len(observations)} native write-queue iteration observations '
              f'across {len(jmh_coordinates)} exact JMH coordinates'
          )
          PY

      - name: Derive repository-aging policy evidence
''',
    "write-queue workflow telemetry validation",
)
workflow = replace_once(
    workflow,
    '''          if [ "${{ matrix.investigation }}" = 'write-queue' ]; then
            echo '- This existing experiment measures striped scheduling and backpressure. Atomic receiver record batching is implemented separately by the production `DurableStripedWriteQueue` and its Hibernate adapter.' >> "$GITHUB_STEP_SUMMARY"
          fi
''',
    '''          if [ "${{ matrix.investigation }}" = 'write-queue' ]; then
            echo '- This existing experiment measures striped scheduling and backpressure. Native PostgreSQL WAL/I/O/wait telemetry is retained per measurement iteration because concurrent invocations overlap and cannot be attributed safely one by one.' >> "$GITHUB_STEP_SUMMARY"
            echo '- Atomic receiver record batching is implemented separately by the production `DurableStripedWriteQueue` and its Hibernate adapter.' >> "$GITHUB_STEP_SUMMARY"
          fi
''',
    "write-queue workflow summary",
)
workflow_path.write_text(workflow, encoding="utf-8")


docs_path = Path("docs/operations/database-native-telemetry.md")
docs = docs_path.read_text(encoding="utf-8")
docs = replace_once(
    docs,
    '''When disabled, the benchmark performs no telemetry connection or telemetry SQL. When explicitly enabled, a snapshot is taken immediately before and after a JMH **measurement invocation**. Both snapshots and JSON serialization are outside the timed benchmark method. Write-path telemetry is captured before benchmark cleanup removes the temporary logical pack.
''',
    '''When disabled, the benchmark performs no telemetry connection or telemetry SQL. When explicitly enabled, snapshots bracket the narrowest boundary that can be attributed safely. The single-threaded pack-layout path captures one exact **measurement invocation** before cleanup. The concurrent write-queue path captures one complete **measurement iteration**, after all worker invocations have drained, because overlapping invocations share cluster-wide counters and cannot be attributed independently without double counting. Snapshot queries and JSON serialization remain outside timed benchmark methods.
''',
    "telemetry boundary documentation",
)
docs = replace_once(
    docs,
    '''The pack-layout runner retains the companion file:

```text
pack-storage-layout-database-telemetry.json
```

beside raw JMH JSON, console output, converted comparison evidence and Surefire reports.
''',
    '''The integrated runners retain companion files beside raw JMH evidence:

```text
pack-storage-layout-database-telemetry.json
write-queue-database-telemetry.json
```

The write-queue coordinate includes the benchmark method, shared versus isolated repository scope, backend/pool variant, execution mode, stripe count, pool size, payload, thread count and measurement-iteration number.
''',
    "write-queue evidence format documentation",
)
docs = replace_once(
    docs,
    '''## Remaining issue #187 work

This foundation does not close #187. Follow-up integrations must apply the same contract to:

1. repository aging, MIDX and repack;
2. durable receiver batches;
3. Hibernate Search incremental indexing and rebuild;
''',
    '''## Concurrent write-queue interpretation

The second integration brackets complete measurement iterations of `DurableWriteQueueBenchmark`. It covers direct publication and one/four/eight-stripe queues, shared-repository contention and independent repositories, and the internal Hibernate pool versus HikariCP at the same pool size. Iteration-level WAL, I/O and wait deltas can therefore be compared with JMH latency, queue delay, storage transactions, repository-lock time and payload amplification without pretending that a cluster-wide counter belongs to one overlapping invocation.

This is evidence for the durable scheduling/publication path, not completion of Git-aware combined receiver-record batching. A queued command still performs its full object insertion and ref update. The narrower atomic multi-record processor and its batch-size distribution remain work in #162 and #187.

## Remaining issue #187 work

This foundation does not close #187. Follow-up integrations must apply the same contract to:

1. repository aging, MIDX and repack;
2. atomic durable receiver-record batches beyond queue scheduling;
3. Hibernate Search incremental indexing and rebuild;
''',
    "write-queue interpretation and remaining scope",
)
docs_path.write_text(docs, encoding="utf-8")


status_path = Path("docs/performance-status.md")
status = status_path.read_text(encoding="utf-8")
status = replace_once(
    status,
    '''- **Physical ceiling:** host, network, database and storage-device throughput. The benchmark telemetry SPI now captures PostgreSQL WAL/I/O and SQL Server log/file-I/O deltas outside timed invocations for the pack-layout path. This is the first direct evidence toward that ceiling; broader benchmark coverage and calibrated attribution remain open in #187.
''',
    '''- **Physical ceiling:** host, network, database and storage-device throughput. The benchmark telemetry SPI captures PostgreSQL WAL/I/O and SQL Server log/file-I/O deltas outside timed pack-layout invocations and PostgreSQL WAL/I/O/wait deltas around complete concurrent write-queue measurement iterations. This is direct evidence toward that ceiling; repository aging, atomic receiver batches, Search paths and calibrated attribution remain open in #187.
''',
    "performance ceiling telemetry scope",
)
status_path.write_text(status, encoding="utf-8")
