#!/usr/bin/env python3
"""Integrate database-native telemetry with the repository-aging benchmark."""

from __future__ import annotations

import re
from pathlib import Path


def add_imports(text: str, imports: set[str]) -> str:
    lines = text.splitlines()
    indices = [index for index, line in enumerate(lines) if line.startswith("import ")]
    if not indices:
        raise SystemExit("Java source has no import block")
    start, end = min(indices), max(indices)
    existing = {line for line in lines[start : end + 1] if line.startswith("import ")}
    static_imports = sorted(line for line in existing if line.startswith("import static "))
    normal_imports = sorted((existing - set(static_imports)) | imports)
    replacement = static_imports
    if static_imports and normal_imports:
        replacement += [""]
    replacement += normal_imports
    return "\n".join(lines[:start] + replacement + lines[end + 1 :]) + "\n"


def extract_params(text: str) -> list[tuple[str, list[str]]]:
    pattern = re.compile(
        r"@Param\s*\(\s*\{(?P<values>.*?)\}\s*\)\s*"
        r"public\s+[A-Za-z0-9_<>?,.\[\] ]+\s+(?P<name>[A-Za-z0-9_]+)\s*;",
        re.DOTALL,
    )
    result: list[tuple[str, list[str]]] = []
    for match in pattern.finditer(text):
        values = re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', match.group("values"))
        result.append((match.group("name"), values))
    if not result:
        raise SystemExit("Could not discover RepositoryAgingBenchmark @Param fields")
    return result


root = Path.cwd()
benchmark_path = root / (
    "jgit-storage-hibernate-benchmarks/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "RepositoryAgingBenchmark.java"
)
test_path = root / (
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "RepositoryAgingNativeTelemetryBenchmarkIT.java"
)
doc_path = root / "docs/operations/database-native-telemetry.md"
workflow_path = root / ".github/workflows/repository-aging-native-telemetry.yml"

benchmark = benchmark_path.read_text(encoding="utf-8")
if "databaseTelemetryBefore" in benchmark:
    raise SystemExit("Repository-aging native telemetry is already integrated")
if "@Setup(Level.Iteration)" in benchmark or "@TearDown(Level.Iteration)" in benchmark:
    raise SystemExit(
        "RepositoryAgingBenchmark already has iteration lifecycle methods; "
        "integrate telemetry into them explicitly"
    )

params = extract_params(benchmark)
param_names = [name for name, _ in params]
backend_field = next((name for name in param_names if name.lower() == "backend"), None)
push_field = next((name for name in param_names if "push" in name.lower()), None)
maintenance_field = next(
    (name for name in param_names if "maintenance" in name.lower()), None
)
if not backend_field or not push_field or not maintenance_field:
    raise SystemExit(
        f"Missing required aging parameters: backend={backend_field}, "
        f"pushes={push_field}, maintenance={maintenance_field}; found {param_names}"
    )

constants = (
    "HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY",
    "HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY",
    "HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY",
    "PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY",
    "PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY",
    "PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY",
)
replacement_count = 0
for constant in constants:
    pattern = re.compile(
        r"(?:requiredSystemProperty|requiredProperty|System\.getProperty)\(\s*"
        + re.escape(constant)
        + r"\s*\)"
    )
    benchmark, count = pattern.subn(
        f"requiredDatabaseTelemetryProperty({constant})", benchmark
    )
    replacement_count += count
if replacement_count < 3:
    raise SystemExit(
        "Could not redirect repository-aging connection properties through "
        f"the ephemeral file contract (replacements={replacement_count})"
    )

benchmark = add_imports(
    benchmark,
    {
        "import java.io.IOException;",
        "import java.io.InputStream;",
        "import java.nio.file.Files;",
        "import java.nio.file.Path;",
        "import java.util.Map;",
        "import java.util.TreeMap;",
        "import java.util.concurrent.atomic.AtomicInteger;",
        "import org.openjdk.jmh.infra.BenchmarkParams;",
        "import org.openjdk.jmh.infra.IterationParams;",
        "import org.openjdk.jmh.runner.IterationType;",
    },
)

field_anchor = re.search(r"\n  @Setup\(Level\.Trial\)", benchmark)
if not field_anchor:
    raise SystemExit("Could not locate RepositoryAgingBenchmark trial setup")
telemetry_fields = """
  private final AtomicInteger databaseTelemetryMeasurementIteration =
      new AtomicInteger();
  private DatabaseTelemetryCollector databaseTelemetryCollector;
  private DatabaseTelemetrySnapshot databaseTelemetryBefore;

  @Setup(Level.Iteration)
  public void setupDatabaseTelemetry(IterationParams iterationParams) {
    if (databaseTelemetryCollector == null) {
      databaseTelemetryCollector = createDatabaseTelemetryCollector();
    }
    databaseTelemetryBefore = null;
    if (databaseTelemetryCollector.enabled()
        && iterationParams.getType() == IterationType.MEASUREMENT) {
      databaseTelemetryBefore = databaseTelemetryCollector.capture();
    }
  }

  @TearDown(Level.Iteration)
  public void tearDownDatabaseTelemetry(
      BenchmarkParams benchmarkParams, IterationParams iterationParams)
      throws IOException {
    try {
      if (databaseTelemetryBefore != null
          && iterationParams.getType() == IterationType.MEASUREMENT) {
        DatabaseTelemetrySnapshot after = databaseTelemetryCollector.capture();
        DatabaseTelemetryJson.appendNdjson(
            requiredDatabaseTelemetryOutput(),
            new DatabaseTelemetryObservation(
                databaseTelemetryCoordinate(
                    benchmarkParams,
                    databaseTelemetryMeasurementIteration.incrementAndGet()),
                databaseTelemetryBefore.deltaTo(after)));
      }
    } finally {
      databaseTelemetryBefore = null;
    }
  }

"""
benchmark = (
    benchmark[: field_anchor.start()]
    + "\n"
    + telemetry_fields
    + benchmark[field_anchor.start() + 1 :]
)

coordinate_lines = [
    f'    coordinate.put("{name}", String.valueOf({name}));' for name in param_names
]
telemetry_methods = """

  private DatabaseTelemetryCollector createDatabaseTelemetryCollector() {
    String databaseBackend = databaseTelemetryBackend();
    boolean enabled =
        Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    if (!enabled) {
      return DatabaseTelemetryCollectors.disabled(
          databaseBackend, "disabled-by-configuration");
    }
    return switch (databaseBackend) {
      case "postgresql" ->
          DatabaseTelemetryCollectors.create(
              databaseBackend,
              true,
              requiredDatabaseTelemetryProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
              requiredDatabaseTelemetryProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
              requiredDatabaseTelemetryProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
      case "sqlserver" ->
          DatabaseTelemetryCollectors.create(
              databaseBackend,
              true,
              requiredDatabaseTelemetryProperty(
                  PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY),
              requiredDatabaseTelemetryProperty(
                  PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY),
              requiredDatabaseTelemetryProperty(
                  PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY));
      default ->
          DatabaseTelemetryCollectors.disabled(
              databaseBackend, "unsupported-backend");
    };
  }

  private String databaseTelemetryBackend() {
    if (backend.startsWith("postgresql")) {
      return "postgresql";
    }
    if (backend.startsWith("sqlserver")) {
      return "sqlserver";
    }
    return backend;
  }

  private Path requiredDatabaseTelemetryOutput() {
    String value =
        System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing benchmark system property "
              + DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    }
    return Path.of(value);
  }

  private Map<String, String> databaseTelemetryCoordinate(
      BenchmarkParams benchmarkParams, int measurementIteration) {
    TreeMap<String, String> coordinate = new TreeMap<>();
    String benchmark = benchmarkParams.getBenchmark();
    coordinate.put(
        "benchmarkMethod", benchmark.substring(benchmark.lastIndexOf('.') + 1));
    coordinate.put(
        "measurementIteration", Integer.toString(measurementIteration));
    coordinate.put("threads", Integer.toString(benchmarkParams.getThreads()));
__COORDINATE_LINES__
    return coordinate;
  }

  private static String requiredDatabaseTelemetryProperty(String name) {
    String value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String connectionPropertiesFile =
        System.getProperty(
            PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY);
    if (connectionPropertiesFile != null
        && !connectionPropertiesFile.isBlank()) {
      Properties properties = new Properties();
      try (InputStream input =
          Files.newInputStream(Path.of(connectionPropertiesFile))) {
        properties.load(input);
      } catch (IOException failure) {
        throw new IllegalStateException(
            "Cannot read temporary benchmark connection properties", failure);
      }
      value = properties.getProperty(name);
    }
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }
""".replace("__COORDINATE_LINES__", "\n".join(coordinate_lines))

closing = benchmark.rfind("\n}")
if closing < 0:
    raise SystemExit("Could not locate RepositoryAgingBenchmark closing brace")
benchmark = benchmark[:closing] + telemetry_methods + benchmark[closing:]
benchmark_path.write_text(benchmark, encoding="utf-8")

param_values = dict(params)
push_values = [value for value in ("1", "10") if value in param_values[push_field]]
if not push_values:
    push_values = param_values[push_field][:2]
maintenance_values = param_values[maintenance_field]
if not push_values or not maintenance_values:
    raise SystemExit(
        f"Could not derive bounded aging matrix: pushes={push_values}, "
        f"maintenance={maintenance_values}"
    )


def java_strings(values: list[str]) -> str:
    return ", ".join(
        '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
        for value in values
    )


integration_test = f'''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.PostgreSQLContainer;

class RepositoryAgingNativeTelemetryBenchmarkIT {{

  private static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.repository-aging.native-telemetry.enabled";

  @TempDir Path temporaryDirectory;

  @Test
  void retainsExactCredentialFreePostgreSqlTelemetry() throws Exception {{
    assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "aging telemetry is opt-in");
    PostgreSQLContainer<?> postgresql =
        new PostgreSQLContainer<>("postgres:17.10-alpine")
            .withDatabaseName("jgit_storage_aging_telemetry")
            .withUsername("aging_benchmark")
            .withPassword("aging-telemetry-password");
    postgresql.start();
    try {{
      runBenchmark(postgresql);
    }} finally {{
      postgresql.stop();
    }}
  }}

  private void runBenchmark(PostgreSQLContainer<?> postgresql) throws Exception {{
    Path root =
        Path.of(
                System.getProperty(
                    "benchmark.outputDirectory", temporaryDirectory.toString()))
            .toAbsolutePath();
    Files.createDirectories(root);
    Path jmh = root.resolve("repository-aging-jmh-result.json");
    Path output = root.resolve("repository-aging-jmh-output.txt");
    Path ndjson = root.resolve("repository-aging-database-telemetry.ndjson");
    Path telemetry = root.resolve("repository-aging-database-telemetry.json");
    Files.deleteIfExists(ndjson);
    Files.deleteIfExists(telemetry);

    Path connectionProperties = writeConnectionProperties(postgresql);
    Collection<RunResult> results;
    try {{
      Options options =
          new OptionsBuilder()
              .include(RepositoryAgingBenchmark.class.getName())
              .param("{backend_field}", HibernateRepositoryBenchmark.POSTGRESQL)
              .param("{push_field}", {java_strings(push_values)})
              .param("{maintenance_field}", {java_strings(maintenance_values)})
              .threads(1)
              .forks(1)
              .warmupIterations(0)
              .warmupTime(TimeValue.milliseconds(50))
              .measurementIterations(1)
              .measurementTime(TimeValue.milliseconds(100))
              .shouldFailOnError(true)
              .resultFormat(ResultFormatType.JSON)
              .result(jmh.toString())
              .output(output.toString())
              .jvmArgsAppend(
                  "-Xms512m",
                  "-Xmx2g",
                  RepositoryBackendBenchmarkIT.systemProperty(
                      PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
                      connectionProperties.toString()),
                  RepositoryBackendBenchmarkIT.systemProperty(
                      DatabaseTelemetryCollectors.ENABLED_PROPERTY, "true"),
                  RepositoryBackendBenchmarkIT.systemProperty(
                      DatabaseTelemetryCollectors.OUTPUT_PROPERTY, ndjson.toString()))
              .build();
      results = new Runner(options).run();
    }} finally {{
      Files.deleteIfExists(connectionProperties);
    }}

    DatabaseTelemetryJson.writeAggregate(ndjson, telemetry);
    assertFalse(results.isEmpty(), "Repository-aging JMH produced no results");
    assertTrue(Files.isRegularFile(jmh));
    assertTrue(Files.isRegularFile(output));
    assertTrue(Files.isRegularFile(telemetry));

    String telemetryText = Files.readString(telemetry);
    long observationCount = telemetryText.split("\\\"coordinate\\\":", -1).length - 1L;
    assertEquals(results.size(), observationCount);
    assertTrue(telemetryText.contains("\\\"backend\\\":\\\"postgresql\\\""));
    assertTrue(telemetryText.contains("\\\"benchmarkMethod\\\":"));
    assertTrue(telemetryText.contains("\\\"{push_field}\\\":"));
    assertTrue(telemetryText.contains("\\\"{maintenance_field}\\\":"));
    assertTrue(telemetryText.contains("postgresql.wal.insert_lsn_bytes"));

    assertCredentialFree(
        List.of(jmh, output, ndjson, telemetry),
        postgresql.getJdbcUrl(),
        postgresql.getUsername(),
        postgresql.getPassword());
  }}

  private static Path writeConnectionProperties(
      PostgreSQLContainer<?> postgresql) throws IOException {{
    Path target =
        Files.createTempFile("jgit-aging-telemetry-connection-", ".properties");
    try {{
      Files.setPosixFilePermissions(
          target, PosixFilePermissions.fromString("rw-------"));
    }} catch (UnsupportedOperationException ignored) {{
      // Supported CI performance runners use POSIX filesystems.
    }}
    Properties properties = new Properties();
    Map.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
            postgresql.getJdbcUrl(),
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
            postgresql.getUsername(),
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
            postgresql.getPassword())
        .forEach(properties::setProperty);
    try (OutputStream output = Files.newOutputStream(target)) {{
      properties.store(output, "ephemeral repository-aging connection properties");
    }} catch (IOException failure) {{
      Files.deleteIfExists(target);
      throw failure;
    }}
    return target;
  }}

  private static void assertCredentialFree(
      List<Path> files, String jdbcUrl, String username, String password)
      throws IOException {{
    List<String> forbidden =
        List.of(
            jdbcUrl,
            username,
            password,
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            "NaN",
            "Infinity");
    for (Path file : files) {{
      String content =
          new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      for (String token : forbidden) {{
        assertFalse(
            content.contains(token),
            () -> "Retained aging evidence " + file + " contains " + token);
      }}
    }}
  }}
}}
'''
test_path.write_text(integration_test, encoding="utf-8")

workflow_path.parent.mkdir(parents=True, exist_ok=True)
workflow_path.write_text(
    '''name: Repository Aging Native Telemetry

on:
  workflow_dispatch:
  schedule:
    - cron: '19 4 7 * *'
  pull_request:
    branches: [ main ]
    paths:
      - '.github/workflows/repository-aging-native-telemetry.yml'
      - 'jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/DatabaseTelemetry*.java'
      - 'jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/RepositoryAgingBenchmark.java'
      - 'jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/RepositoryAgingNativeTelemetryBenchmarkIT.java'
      - 'docs/operations/database-native-telemetry.md'

concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  postgresql-smoke:
    name: PostgreSQL aging/repack telemetry smoke
    runs-on: ubuntu-latest
    timeout-minutes: 120

    steps:
      - name: Checkout
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1

      - name: Set up JDK 21
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Verify Docker
        run: docker info

      - name: Build benchmark module
        run: mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests

      - name: Run exact repository-aging telemetry smoke
        run: |
          mkdir -p target/repository-aging-native-telemetry
          mvn -B -pl jgit-storage-hibernate-benchmarks test \
            -Dtest=RepositoryAgingNativeTelemetryBenchmarkIT \
            -Djgit.storage.benchmark.repository-aging.native-telemetry.enabled=true \
            -Dbenchmark.outputDirectory="$GITHUB_WORKSPACE/target/repository-aging-native-telemetry"

      - name: Verify strict native evidence
        shell: bash
        run: |
          python3 - <<'PY'
          import json
          from pathlib import Path

          root = Path('target/repository-aging-native-telemetry')
          telemetry_path = root / 'repository-aging-database-telemetry.json'
          jmh_path = root / 'repository-aging-jmh-result.json'
          telemetry_text = telemetry_path.read_text(encoding='utf-8')
          jmh = json.loads(jmh_path.read_text(encoding='utf-8'))
          telemetry = json.loads(telemetry_text)
          observations = telemetry['observations']
          if not observations:
              raise SystemExit('Expected repository-aging native observations')
          if len(observations) != len(jmh):
              raise SystemExit(
                  f'JMH/telemetry cardinality mismatch: {len(jmh)} != {len(observations)}'
              )
          for observation in observations:
              coordinate = observation['coordinate']
              if observation['backend'] != 'postgresql':
                  raise SystemExit(f'Unexpected backend: {observation["backend"]}')
              if 'benchmarkMethod' not in coordinate:
                  raise SystemExit(f'Missing benchmark method: {coordinate}')
              if 'postgresql.wal.insert_lsn_bytes' not in observation['counters']:
                  raise SystemExit(f'Missing immediate WAL counter: {coordinate}')
          if 'NaN' in telemetry_text or 'Infinity' in telemetry_text:
              raise SystemExit('Repository-aging telemetry is not strict JSON')
          print(f'Validated {len(observations)} exact aging/repack observations')
          PY

      - name: Upload repository-aging telemetry evidence
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: repository-aging-postgresql-native-telemetry-smoke
          path: |
            target/repository-aging-native-telemetry/**
            jgit-storage-hibernate-benchmarks/target/surefire-reports/**
          if-no-files-found: error
''',
    encoding="utf-8",
)

doc = doc_path.read_text(encoding="utf-8")
anchor = "## Remaining issue #187 work\n"
if anchor not in doc:
    raise SystemExit("Could not locate native-telemetry remaining-work section")
section = '''## Repository aging and maintenance integration

The repository-aging benchmark now records one PostgreSQL native telemetry delta for every JMH measurement coordinate. The companion evidence retains the benchmark method, all aging parameters (including push count and maintenance mode), measurement iteration and thread count together with immediate WAL, cumulative I/O, wait, database and optional statement deltas.

A bounded pull-request smoke matrix covers the first and tenth incremental push across all currently declared maintenance modes. It asserts one telemetry observation per JMH result, strict JSON and absence of the temporary Testcontainers connection values from every retained artifact. This establishes the measurement contract; it does **not** yet justify automatic maintenance.

The next #165 evidence slice remains cold versus warm process state, restart-before-measurement, reads concurrent with maintenance and the full PostgreSQL/SQL Server scale matrix. Production maintenance therefore remains opt-in and condition-based.

'''
if "## Repository aging and maintenance integration" not in doc:
    doc = doc.replace(anchor, section + anchor)
doc_path.write_text(doc, encoding="utf-8")
