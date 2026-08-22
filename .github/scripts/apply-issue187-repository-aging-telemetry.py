#!/usr/bin/env python3
"""Integrate native telemetry with repository aging, maintenance and read phases."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


benchmark_path = Path(
    "jgit-storage-hibernate-benchmarks/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "RepositoryAgingBenchmark.java"
)
benchmark = benchmark_path.read_text(encoding="utf-8")
benchmark = replace_once(
    benchmark,
    '''import java.io.InputStream;
import java.nio.charset.StandardCharsets;
''',
    '''import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
''',
    "repository-aging file imports",
)
benchmark = replace_once(
    benchmark,
    '''import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
''',
    '''import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
''',
    "repository-aging telemetry imports",
)
benchmark = replace_once(
    benchmark,
    '''import org.openjdk.jmh.annotations.Warmup;
''',
    '''import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
''',
    "repository-aging JMH telemetry imports",
)
benchmark = replace_once(
    benchmark,
    '''  private PackRepackResult repackResult;
  private long unreachableLogicalBytes;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    repositoryName =
        "jmh-aging-"
            + backend
            + "-"
            + pushes
            + "-"
            + maintenanceMode
            + "-"
            + cacheState
            + "-"
            + Long.toHexString(System.nanoTime());
    provider = new HibernateSessionFactoryProvider(properties());
    statistics = provider.getSessionFactory().getStatistics();
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    buildDeterministicHistory();
    closeAndReopen();
    verifyPersistedPackOrdering();
    applyMaintenance();
    closeAndReopen();
    verifyPersistedPackOrdering();
    verifyReachableFixture();
    inventory = inventory();
    if (WARM.equals(cacheState)) {
      warmRepositoryCache();
    } else if (!COLD.equals(cacheState)) {
      throw new IllegalArgumentException("Unsupported cache state " + cacheState);
    }
  }

  @Setup(Level.Invocation)
''',
    '''  private PackRepackResult repackResult;
  private long unreachableLogicalBytes;
  private final AtomicInteger measurementIteration = new AtomicInteger();
  private DatabaseTelemetryCollector telemetryCollector;
  private DatabaseTelemetrySnapshot iterationTelemetryBefore;
  private String benchmarkMethod;

  @Setup(Level.Trial)
  public void setupTrial(BenchmarkParams benchmarkParams) throws Exception {
    setupTrial(benchmarkMethod(benchmarkParams));
  }

  public void setupTrial() throws Exception {
    setupTrial("unit-test");
  }

  private void setupTrial(String benchmarkMethod) throws Exception {
    suppressConnectionMetadataLogging();
    this.benchmarkMethod = benchmarkMethod;
    repositoryName =
        "jmh-aging-"
            + backend
            + "-"
            + pushes
            + "-"
            + maintenanceMode
            + "-"
            + cacheState
            + "-"
            + Long.toHexString(System.nanoTime());
    provider = new HibernateSessionFactoryProvider(properties());
    statistics = provider.getSessionFactory().getStatistics();
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    telemetryCollector = databaseTelemetryCollector();
    captureSetupPhase("fixture-build", this::buildDeterministicHistory);
    closeAndReopen();
    verifyPersistedPackOrdering();
    captureSetupPhase("maintenance", this::applyMaintenance);
    closeAndReopen();
    verifyPersistedPackOrdering();
    verifyReachableFixture();
    inventory = inventory();
    if (WARM.equals(cacheState)) {
      warmRepositoryCache();
    } else if (!COLD.equals(cacheState)) {
      throw new IllegalArgumentException("Unsupported cache state " + cacheState);
    }
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
  public void tearDownIteration(IterationParams iterationParams) throws IOException {
    try {
      if (iterationTelemetryBefore != null
          && iterationParams.getType() == IterationType.MEASUREMENT) {
        DatabaseTelemetrySnapshot after = telemetryCollector.capture();
        appendTelemetry(
            "measurement",
            measurementIteration.incrementAndGet(),
            iterationTelemetryBefore.deltaTo(after));
      }
    } finally {
      iterationTelemetryBefore = null;
    }
  }

  @Setup(Level.Invocation)
''',
    "repository-aging telemetry lifecycle",
)
benchmark = replace_once(
    benchmark,
    '''  public void tearDownTrial() {
    if (repository != null) {
''',
    '''  public void tearDownTrial() {
    iterationTelemetryBefore = null;
    if (telemetryCollector != null) {
      telemetryCollector.close();
      telemetryCollector = null;
    }
    if (repository != null) {
''',
    "repository-aging telemetry cleanup",
)
benchmark = replace_once(
    benchmark,
    '''  private void buildDeterministicHistory() throws Exception {
''',
    '''  private void captureSetupPhase(String phase, CheckedAction action)
      throws Exception {
    if (!telemetryCollector.enabled()) {
      action.run();
      return;
    }
    DatabaseTelemetrySnapshot before = telemetryCollector.capture();
    action.run();
    DatabaseTelemetrySnapshot after = telemetryCollector.capture();
    appendTelemetry(phase, 0, before.deltaTo(after));
  }

  private void appendTelemetry(
      String phase, int iteration, DatabaseTelemetryDelta telemetry)
      throws IOException {
    DatabaseTelemetryJson.appendNdjson(
        requiredTelemetryOutput(),
        new DatabaseTelemetryObservation(
            telemetryCoordinate(phase, iteration), telemetry));
  }

  private DatabaseTelemetryCollector databaseTelemetryCollector() {
    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    if (!enabled) {
      return DatabaseTelemetryCollectors.disabled(
          databaseBackend(), "disabled-by-configuration");
    }
    if (HibernateRepositoryBenchmark.HSQLDB.equals(backend)) {
      return DatabaseTelemetryCollectors.disabled(
          HibernateRepositoryBenchmark.HSQLDB, "unsupported-backend");
    }
    return DatabaseTelemetryCollectors.create(
        "postgresql",
        true,
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
  }

  private String databaseBackend() {
    return HibernateRepositoryBenchmark.HSQLDB.equals(backend)
        ? HibernateRepositoryBenchmark.HSQLDB
        : "postgresql";
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

  private Map<String, String> telemetryCoordinate(String phase, int iteration) {
    return Map.of(
        "backend", backend,
        "benchmarkMethod", benchmarkMethod,
        "cacheState", cacheState,
        "databaseBackend", databaseBackend(),
        "deployment", deployment,
        "maintenanceMode", maintenanceMode,
        "measurementIteration", Integer.toString(iteration),
        "phase", phase,
        "pushes", Integer.toString(pushes));
  }

  private static String benchmarkMethod(BenchmarkParams benchmarkParams) {
    String benchmark = benchmarkParams.getBenchmark();
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }

  private void buildDeterministicHistory() throws Exception {
''',
    "repository-aging telemetry helpers",
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

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }
''',
    "repository-aging connection properties",
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
    '''  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.investigation.profile";
''',
    '''  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.investigation.profile";
  private static final String REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY =
      "jgit.storage.benchmark.repository-aging.native-smoke";
''',
    "native aging smoke property",
)
runner = replace_once(
    runner,
    '''    OptionsBuilder builder = baseOptions(resultFile, outputFile, full);
    boolean writeQueue = "write-queue".equals(investigation);
    boolean telemetryEnabled =
        writeQueue
            && Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    Path telemetryNdjson =
        resultFile.resolveSibling("write-queue-database-telemetry.ndjson");
    Path telemetryJson =
        resultFile.resolveSibling("write-queue-database-telemetry.json");
''',
    '''    OptionsBuilder builder = baseOptions(resultFile, outputFile, full);
    boolean writeQueue = "write-queue".equals(investigation);
    boolean repositoryAging = "repository-aging".equals(investigation);
    boolean repositoryAgingNativeSmoke =
        repositoryAging
            && Boolean.getBoolean(REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY);
    boolean telemetryEnabled =
        (writeQueue || repositoryAging)
            && Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    String telemetryPrefix = writeQueue ? "write-queue" : "repository-aging";
    Path telemetryNdjson =
        resultFile.resolveSibling(telemetryPrefix + "-database-telemetry.ndjson");
    Path telemetryJson =
        resultFile.resolveSibling(telemetryPrefix + "-database-telemetry.json");
''',
    "generic investigation telemetry paths",
)
runner = replace_once(
    runner,
    '''    if (writeQueue) {
      connectionPropertiesFile = writeConnectionProperties();
''',
    '''    if (writeQueue || repositoryAging) {
      connectionPropertiesFile = writeConnectionProperties();
''',
    "repository-aging temporary credentials",
)
runner = replace_once(
    runner,
    '''    configure(builder, investigation, full, deployment, threads);
''',
    '''    configure(
        builder,
        investigation,
        full,
        deployment,
        threads,
        repositoryAgingNativeSmoke);
''',
    "native aging configure argument",
)
runner = replace_once(
    runner,
    '''      assertTrue(
          Files.isRegularFile(telemetryJson),
          "Write-queue telemetry JSON was not written");
      assertTrue(
          Files.size(telemetryJson) > 32,
          "Write-queue telemetry JSON is empty");
''',
    '''      assertTrue(
          Files.isRegularFile(telemetryJson),
          investigation + " telemetry JSON was not written");
      assertTrue(
          Files.size(telemetryJson) > 32,
          investigation + " telemetry JSON is empty");
''',
    "generic telemetry assertions",
)
runner = replace_once(
    runner,
    '''    if (writeQueue) {
      List<Path> retainedEvidence = new ArrayList<>();
''',
    '''    if (writeQueue || repositoryAging) {
      List<Path> retainedEvidence = new ArrayList<>();
''',
    "repository-aging evidence privacy scan",
)
runner = replace_once(
    runner,
    '''  private static Path writeConnectionProperties() throws IOException {
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
''',
    '''  private static Path writeConnectionProperties() throws IOException {
    Path target = null;
    try {
      target =
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
      }
      return target;
    } catch (IOException | RuntimeException failure) {
      if (target != null) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }
''',
    "robust temporary credential cleanup",
)
runner = replace_once(
    runner,
    '''  private static void configure(
      OptionsBuilder builder,
      String investigation,
      boolean full,
      String deployment,
      int threads) {
''',
    '''  private static void configure(
      OptionsBuilder builder,
      String investigation,
      boolean full,
      String deployment,
      int threads,
      boolean repositoryAgingNativeSmoke) {
''',
    "native aging configure signature",
)
runner = replace_once(
    runner,
    '''      case "repository-aging" ->
          builder
              .include(RepositoryAgingBenchmark.class.getName())
              .threads(1)
              .param(
                  "backend",
                  full
                      ? new String[] {
                        HibernateRepositoryBenchmark.HSQLDB,
                        HibernateRepositoryBenchmark.POSTGRESQL,
                        HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
                      }
                      : new String[] {HibernateRepositoryBenchmark.HSQLDB})
              .param(
                  "pushes",
                  full
                      ? new String[] {"1", "10", "32", "100", "300", "1000"}
                      : new String[] {"1", "10"})
''',
    '''      case "repository-aging" ->
          builder
              .include(
                  repositoryAgingNativeSmoke
                      ? RepositoryAgingBenchmark.class.getName()
                          + ".(lookupOldestObject|cloneStyleTraversal|reopenAndLookupOldest)"
                      : RepositoryAgingBenchmark.class.getName())
              .threads(1)
              .param(
                  "backend",
                  repositoryAgingNativeSmoke
                      ? new String[] {HibernateRepositoryBenchmark.POSTGRESQL}
                      : full
                          ? new String[] {
                            HibernateRepositoryBenchmark.HSQLDB,
                            HibernateRepositoryBenchmark.POSTGRESQL,
                            HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
                          }
                          : new String[] {HibernateRepositoryBenchmark.HSQLDB})
              .param(
                  "pushes",
                  repositoryAgingNativeSmoke
                      ? new String[] {"10"}
                      : full
                          ? new String[] {"1", "10", "32", "100", "300", "1000"}
                          : new String[] {"1", "10"})
''',
    "bounded native aging profile",
)
runner_path.write_text(runner, encoding="utf-8")


workflow_path = Path(".github/workflows/performance-investigations.yml")
workflow = workflow_path.read_text(encoding="utf-8")
workflow = replace_once(
    workflow,
    '''      - name: Validate write-queue native telemetry
''',
    '''      - name: Run exact PostgreSQL repository-aging native telemetry smoke
        if: matrix.investigation == 'repository-aging'
        shell: bash
        run: |
          set -euo pipefail
          native_root="$GITHUB_WORKSPACE/target/investigations/repository-aging/native"
          rm -rf "$native_root"
          mkdir -p "$native_root"
          mvn -B -pl jgit-storage-hibernate-benchmarks verify \\
            -Pperformance-investigations \\
            -Dtest=DatabaseTelemetrySnapshotTest \\
            -Djgit.storage.benchmark.investigation=repository-aging \\
            -Djgit.storage.benchmark.investigation.profile=smoke \\
            -Djgit.storage.benchmark.repository-aging.native-smoke=true \\
            -Djgit.storage.benchmark.database-telemetry.enabled=true \\
            -Dbenchmark.resultFile="$native_root/jmh-result.json" 2>&1 \\
            | tee "$native_root/maven-benchmark.log"

      - name: Validate write-queue native telemetry
''',
    "native aging smoke run",
)
workflow = replace_once(
    workflow,
    '''      - name: Derive repository-aging policy evidence
''',
    '''      - name: Validate repository-aging native telemetry
        if: matrix.investigation == 'repository-aging'
        shell: bash
        run: |
          python3 - <<'PY'
          import collections
          import datetime
          import json
          from pathlib import Path

          root = Path('target/investigations/repository-aging/native')
          telemetry_path = root / 'repository-aging-database-telemetry.json'
          ndjson_path = root / 'repository-aging-database-telemetry.ndjson'
          jmh_path = root / 'jmh-result.json'
          for path in (telemetry_path, ndjson_path, jmh_path):
              if not path.is_file() or path.stat().st_size < 3:
                  raise SystemExit(f'Missing repository-aging evidence {path}')

          telemetry_text = telemetry_path.read_text(encoding='utf-8')
          if 'NaN' in telemetry_text or 'Infinity' in telemetry_text:
              raise SystemExit('Repository-aging telemetry is not strict JSON')
          observations = json.loads(telemetry_text)['observations']
          jmh = json.loads(jmh_path.read_text(encoding='utf-8'))
          if len(jmh) != 9:
              raise SystemExit(f'Expected 9 focused JMH results, found {len(jmh)}')
          if len(observations) != 27:
              raise SystemExit(
                  f'Expected 27 phase observations, found {len(observations)}'
              )

          def base_from_jmh(item):
              params = item['params']
              return (
                  item['benchmark'].rsplit('.', 1)[-1],
                  params['backend'],
                  params['pushes'],
                  params['maintenanceMode'],
                  params['cacheState'],
                  params['deployment'],
              )

          def base_from_observation(item):
              coordinate = item['coordinate']
              return (
                  coordinate['benchmarkMethod'],
                  coordinate['backend'],
                  coordinate['pushes'],
                  coordinate['maintenanceMode'],
                  coordinate['cacheState'],
                  coordinate['deployment'],
              )

          jmh_bases = {base_from_jmh(item) for item in jmh}
          phase_counts = collections.Counter(
              (base_from_observation(item), item['coordinate']['phase'])
              for item in observations
          )
          if {base for base, _ in phase_counts} != jmh_bases:
              raise SystemExit(
                  'JMH and telemetry base coordinates differ: '
                  f'{jmh_bases ^ {base for base, _ in phase_counts}}'
              )
          expected_phases = {'fixture-build', 'maintenance', 'measurement'}
          for base in jmh_bases:
              phases = {
                  phase
                  for (candidate, phase), count in phase_counts.items()
                  if candidate == base and count == 1
              }
              if phases != expected_phases:
                  raise SystemExit(f'Incomplete phases for {base}: {phases}')

          wal_by_phase = collections.defaultdict(list)
          for item in observations:
              coordinate = item['coordinate']
              phase = coordinate['phase']
              if item['backend'] != 'postgresql':
                  raise SystemExit(f'Unexpected telemetry backend: {item["backend"]}')
              if coordinate['databaseBackend'] != 'postgresql':
                  raise SystemExit(f'Unexpected database coordinate: {coordinate}')
              expected_iteration = '1' if phase == 'measurement' else '0'
              if coordinate['measurementIteration'] != expected_iteration:
                  raise SystemExit(f'Unexpected phase iteration: {coordinate}')
              for field in ('coordinate', 'counters', 'gauges', 'metadata', 'unsupported'):
                  keys = list(item[field])
                  if keys != sorted(keys):
                      raise SystemExit(
                          f'{field} keys are not deterministic for {coordinate}: {keys}'
                      )
              wal = item['counters'].get('postgresql.wal.insert_lsn_bytes')
              if wal is None or wal < 0:
                  raise SystemExit(f'Missing immediate WAL delta for {coordinate}: {wal}')
              wal_by_phase[phase].append(wal)
              if phase == 'fixture-build' and wal <= 0:
                  raise SystemExit(f'Fixture build produced no WAL: {coordinate}')
              if (
                  phase == 'maintenance'
                  and coordinate['maintenanceMode'] != 'none'
                  and wal <= 0
              ):
                  raise SystemExit(f'Maintenance produced no WAL: {coordinate}')
              start = datetime.datetime.fromisoformat(
                  item['startedAt'].replace('Z', '+00:00')
              )
              end = datetime.datetime.fromisoformat(
                  item['completedAt'].replace('Z', '+00:00')
              )
              if end < start:
                  raise SystemExit(f'Negative telemetry window for {coordinate}')

          ndjson_lines = [
              line for line in ndjson_path.read_text(encoding='utf-8').splitlines() if line
          ]
          if len(ndjson_lines) != len(observations):
              raise SystemExit(
                  f'NDJSON/aggregate count mismatch: {len(ndjson_lines)} != {len(observations)}'
              )
          for line in ndjson_lines:
              json.loads(line)

          forbidden = (
              'jgit.storage.benchmark.postgresql.url=',
              'jgit.storage.benchmark.postgresql.user=',
              'jgit.storage.benchmark.postgresql.password=',
              'jdbc:postgresql://',
              'Database JDBC URL',
              'Default catalog/schema',
          )
          for path in root.rglob('*'):
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
              'Validated 9 repository-aging JMH coordinates and 27 phase observations; '
              + ', '.join(
                  f'{phase} WAL {min(values)}..{max(values)} bytes'
                  for phase, values in sorted(wal_by_phase.items())
              )
          )
          PY

      - name: Derive repository-aging policy evidence
''',
    "native aging evidence validation",
)
workflow = replace_once(
    workflow,
    '''          if [ "${{ matrix.investigation }}" = 'repository-aging' ]; then
            echo '- Generated comparison and policy-evidence JSON retain measured break-even reads; no automatic maintenance trigger is changed by this workflow.' >> "$GITHUB_STEP_SUMMARY"
          fi
''',
    '''          if [ "${{ matrix.investigation }}" = 'repository-aging' ]; then
            echo '- Generated comparison and policy-evidence JSON retain measured break-even reads; no automatic maintenance trigger is changed by this workflow.' >> "$GITHUB_STEP_SUMMARY"
            echo '- A focused PostgreSQL sub-run retains native fixture-build, maintenance and measurement-phase WAL/I/O/wait evidence with exact JMH coordinates.' >> "$GITHUB_STEP_SUMMARY"
          fi
''',
    "repository-aging telemetry workflow summary",
)
workflow_path.write_text(workflow, encoding="utf-8")


docs_path = Path("docs/operations/database-native-telemetry.md")
docs = docs_path.read_text(encoding="utf-8")
docs = replace_once(
    docs,
    '''The integrated runners retain companion files beside raw JMH evidence:

```text
pack-storage-layout-database-telemetry.json
write-queue-database-telemetry.json
```

The write-queue coordinate includes the benchmark method, shared versus isolated repository scope, backend/pool variant, execution mode, stripe count, pool size, payload, thread count and measurement-iteration number.
''',
    '''The integrated runners retain companion files beside raw JMH evidence:

```text
pack-storage-layout-database-telemetry.json
write-queue-database-telemetry.json
repository-aging-database-telemetry.json
```

The write-queue coordinate includes the benchmark method, shared versus isolated repository scope, backend/pool variant, execution mode, stripe count, pool size, payload, thread count and measurement-iteration number. Repository-aging coordinates add pushes, maintenance mode, cache state and one of three explicit phases: deterministic fixture build, maintenance, or measured read operation.
''',
    "repository-aging evidence format",
)
docs = replace_once(
    docs,
    '''## Remaining issue #187 work

This foundation does not close #187. Follow-up integrations must apply the same contract to:

1. repository aging, MIDX and repack;
2. atomic durable receiver-record batches beyond queue scheduling;
3. Hibernate Search incremental indexing and rebuild;
''',
    '''## Repository-aging and maintenance interpretation

The focused PostgreSQL aging sub-run separates three boundaries for every selected JMH coordinate:

1. deterministic incremental fixture publication;
2. the selected maintenance action (`none`, compact-only or read-optimized);
3. the later measured lookup/traversal/reopen iteration.

This prevents repack WAL and I/O from being misattributed to the read that benefits from it. The initial bounded profile uses ten pushes and representative oldest-object, clone-style and reopen lookups. It proves attribution and artifact contracts; the 32/100/300/1,000-push production matrix and SQL Server aging telemetry remain open.

## Remaining issue #187 work

This foundation does not close #187. Follow-up integrations must apply the same contract to:

1. full-scale repository aging/MIDX/repack and SQL Server;
2. atomic durable receiver-record batches beyond queue scheduling;
3. Hibernate Search incremental indexing and rebuild;
''',
    "repository-aging interpretation and remaining scope",
)
docs_path.write_text(docs, encoding="utf-8")


status_path = Path("docs/performance-status.md")
status = status_path.read_text(encoding="utf-8")
status = replace_once(
    status,
    '''- **Physical ceiling:** host, network, database and storage-device throughput. The benchmark telemetry SPI captures PostgreSQL WAL/I/O and SQL Server log/file-I/O deltas outside timed pack-layout invocations and PostgreSQL WAL/I/O/wait deltas around complete concurrent write-queue measurement iterations. This is direct evidence toward that ceiling; repository aging, atomic receiver batches, Search paths and calibrated attribution remain open in #187.
''',
    '''- **Physical ceiling:** host, network, database and storage-device throughput. The benchmark telemetry SPI captures PostgreSQL WAL/I/O and SQL Server log/file-I/O deltas outside timed pack-layout invocations, PostgreSQL WAL/I/O/wait deltas around complete concurrent write-queue measurement iterations, and separated PostgreSQL fixture-build, maintenance and measured-read phases for a bounded repository-aging profile. Full-scale aging/SQL Server, atomic receiver batches, Search paths and calibrated attribution remain open in #187.
''',
    "performance ceiling repository-aging scope",
)
status_path.write_text(status, encoding="utf-8")
