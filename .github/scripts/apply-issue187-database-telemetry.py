#!/usr/bin/env python3
"""Integrate database-native telemetry around exact pack-layout invocations."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


benchmark_path = Path(
    "jgit-storage-hibernate-benchmarks/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PackStorageLayoutBenchmark.java"
)
benchmark = benchmark_path.read_text(encoding="utf-8")
if "databaseTelemetryCollector()" not in benchmark:
    benchmark = replace_once(
        benchmark,
        "import java.time.Instant;\n",
        "import java.io.IOException;\nimport java.nio.file.Path;\nimport java.time.Instant;\n",
        "benchmark telemetry imports",
    )
    benchmark = replace_once(
        benchmark,
        "import java.util.List;\n",
        "import java.util.List;\nimport java.util.Map;\n",
        "benchmark map import",
    )
    benchmark = replace_once(
        benchmark,
        "import org.openjdk.jmh.annotations.Warmup;\n",
        "import org.openjdk.jmh.annotations.Warmup;\n"
        "import org.openjdk.jmh.infra.IterationParams;\n"
        "import org.openjdk.jmh.runner.IterationType;\n",
        "benchmark JMH iteration imports",
    )
    benchmark = replace_once(
        benchmark,
        "  private Long fixturePackId;\n  private Long invocationPackId;\n",
        "  private Long fixturePackId;\n"
        "  private Long invocationPackId;\n"
        "  private DatabaseTelemetryCollector telemetryCollector;\n"
        "  private DatabaseTelemetrySnapshot invocationTelemetryBefore;\n",
        "benchmark telemetry fields",
    )
    benchmark = replace_once(
        benchmark,
        "    if (!WRITE.equals(operation)) {\n"
        "      fixturePackId = persistLayout(\"fixture\");\n"
        "    }\n"
        "  }\n\n"
        "  @Setup(Level.Invocation)\n"
        "  public void setupInvocation() {\n"
        "    invocationPackId = null;\n"
        "    statistics.clear();\n"
        "    JdbcBatchMetricsSessionEventListener.resetCurrentThread();\n"
        "  }\n\n"
        "  @TearDown(Level.Invocation)\n"
        "  public void tearDownInvocation() {\n"
        "    if (invocationPackId != null) {\n"
        "      deletePack(invocationPackId);\n"
        "      invocationPackId = null;\n"
        "    }\n"
        "  }\n",
        "    if (!WRITE.equals(operation)) {\n"
        "      fixturePackId = persistLayout(\"fixture\");\n"
        "    }\n"
        "    telemetryCollector = databaseTelemetryCollector();\n"
        "  }\n\n"
        "  @Setup(Level.Invocation)\n"
        "  public void setupInvocation(IterationParams iterationParams) {\n"
        "    invocationPackId = null;\n"
        "    invocationTelemetryBefore = null;\n"
        "    statistics.clear();\n"
        "    JdbcBatchMetricsSessionEventListener.resetCurrentThread();\n"
        "    if (telemetryCollector.enabled()\n"
        "        && iterationParams.getType() == IterationType.MEASUREMENT) {\n"
        "      invocationTelemetryBefore = telemetryCollector.capture();\n"
        "    }\n"
        "  }\n\n"
        "  @TearDown(Level.Invocation)\n"
        "  public void tearDownInvocation(IterationParams iterationParams) throws IOException {\n"
        "    try {\n"
        "      if (invocationTelemetryBefore != null\n"
        "          && iterationParams.getType() == IterationType.MEASUREMENT) {\n"
        "        DatabaseTelemetrySnapshot after = telemetryCollector.capture();\n"
        "        DatabaseTelemetryJson.appendNdjson(\n"
        "            requiredTelemetryOutput(),\n"
        "            new DatabaseTelemetryObservation(\n"
        "                telemetryCoordinate(), invocationTelemetryBefore.deltaTo(after)));\n"
        "      }\n"
        "    } finally {\n"
        "      invocationTelemetryBefore = null;\n"
        "      if (invocationPackId != null) {\n"
        "        deletePack(invocationPackId);\n"
        "        invocationPackId = null;\n"
        "      }\n"
        "    }\n"
        "  }\n",
        "benchmark invocation telemetry integration",
    )
    benchmark = replace_once(
        benchmark,
        "  @TearDown(Level.Trial)\n"
        "  public void tearDownTrial() {\n"
        "    fixturePackId = null;\n"
        "    statistics = null;\n"
        "    sessionFactory = null;\n"
        "    if (provider != null) {\n"
        "      provider.close();\n"
        "      provider = null;\n"
        "    }\n"
        "  }\n",
        "  @TearDown(Level.Trial)\n"
        "  public void tearDownTrial() {\n"
        "    fixturePackId = null;\n"
        "    invocationTelemetryBefore = null;\n"
        "    if (telemetryCollector != null) {\n"
        "      telemetryCollector.close();\n"
        "      telemetryCollector = null;\n"
        "    }\n"
        "    statistics = null;\n"
        "    sessionFactory = null;\n"
        "    if (provider != null) {\n"
        "      provider.close();\n"
        "      provider = null;\n"
        "    }\n"
        "  }\n",
        "benchmark telemetry cleanup",
    )
    benchmark = replace_once(
        benchmark,
        "  private Properties properties() {\n",
        "  private DatabaseTelemetryCollector databaseTelemetryCollector() {\n"
        "    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);\n"
        "    return switch (backend) {\n"
        "      case HibernateRepositoryBenchmark.POSTGRESQL ->\n"
        "          DatabaseTelemetryCollectors.create(\n"
        "              backend,\n"
        "              enabled,\n"
        "              requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),\n"
        "              requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),\n"
        "              requiredSystemProperty(\n"
        "                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));\n"
        "      case SQL_SERVER ->\n"
        "          DatabaseTelemetryCollectors.create(\n"
        "              backend,\n"
        "              enabled,\n"
        "              requiredSystemProperty(SQL_SERVER_URL_PROPERTY),\n"
        "              requiredSystemProperty(SQL_SERVER_USER_PROPERTY),\n"
        "              requiredSystemProperty(SQL_SERVER_PASSWORD_PROPERTY));\n"
        "      default -> DatabaseTelemetryCollectors.disabled(backend, \"unsupported-backend\");\n"
        "    };\n"
        "  }\n\n"
        "  private Path requiredTelemetryOutput() {\n"
        "    String value = System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY);\n"
        "    if (value == null || value.isBlank()) {\n"
        "      throw new IllegalStateException(\n"
        "          \"Missing benchmark system property \"\n"
        "              + DatabaseTelemetryCollectors.OUTPUT_PROPERTY);\n"
        "    }\n"
        "    return Path.of(value);\n"
        "  }\n\n"
        "  private Map<String, String> telemetryCoordinate() {\n"
        "    return Map.of(\n"
        "        \"backend\", backend,\n"
        "        \"deployment\", deployment,\n"
        "        \"operation\", operation,\n"
        "        \"payloadKiB\", Integer.toString(payloadKiB),\n"
        "        \"chunkKiB\", Integer.toString(chunkKiB),\n"
        "        \"inlineKiB\", Integer.toString(inlineKiB),\n"
        "        \"retainedMiB\", Integer.toString(retainedMiB),\n"
        "        \"readAheadKiB\", Integer.toString(readAheadKiB));\n"
        "  }\n\n"
        "  private Properties properties() {\n",
        "benchmark telemetry helpers",
    )
    benchmark_path.write_text(benchmark, encoding="utf-8")


test_path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PackStorageLayoutBenchmarkTest.java"
)
test = test_path.read_text(encoding="utf-8")
if "pack-storage-layout-database-telemetry.json" not in test:
    test = replace_once(
        test,
        "    Path rawDirectory = resultFile.getParent().resolve(\"raw\");\n"
        "    Files.createDirectories(rawDirectory);\n",
        "    Path rawDirectory = resultFile.getParent().resolve(\"raw\");\n"
        "    Files.createDirectories(rawDirectory);\n"
        "    Path telemetryNdjson = rawDirectory.resolve(\"database-telemetry.ndjson\");\n"
        "    Files.deleteIfExists(telemetryNdjson);\n",
        "layout harness telemetry path",
    )
    test = replace_once(
        test,
        "    mergeJsonArrays(rawResults, resultFile);\n"
        "    mergeTextOutputs(rawOutputs, resultFile.resolveSibling(\"pack-storage-layout-jmh-output.txt\"));\n"
        "    assertTrue(resultCount > 0);\n",
        "    mergeJsonArrays(rawResults, resultFile);\n"
        "    mergeTextOutputs(\n"
        "        rawOutputs, resultFile.resolveSibling(\"pack-storage-layout-jmh-output.txt\"));\n"
        "    if (Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY)) {\n"
        "      Path telemetry =\n"
        "          resultFile.resolveSibling(\"pack-storage-layout-database-telemetry.json\");\n"
        "      DatabaseTelemetryJson.writeAggregate(telemetryNdjson, telemetry);\n"
        "      assertTrue(Files.isRegularFile(telemetry));\n"
        "      assertTrue(Files.size(telemetry) > 32);\n"
        "    }\n"
        "    assertTrue(resultCount > 0);\n",
        "layout harness telemetry aggregation",
    )
    test = replace_once(
        test,
        "    jvmArguments.addAll(target.jvmArguments());\n\n"
        "    return new OptionsBuilder()\n",
        "    jvmArguments.addAll(target.jvmArguments());\n"
        "    if (Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY)\n"
        "        && !HibernateRepositoryBenchmark.HSQLDB.equals(backend)) {\n"
        "      jvmArguments.add(\n"
        "          RepositoryBackendBenchmarkIT.systemProperty(\n"
        "              DatabaseTelemetryCollectors.ENABLED_PROPERTY, \"true\"));\n"
        "      jvmArguments.add(\n"
        "          RepositoryBackendBenchmarkIT.systemProperty(\n"
        "              DatabaseTelemetryCollectors.OUTPUT_PROPERTY,\n"
        "              resultFile\n"
        "                  .getParent()\n"
        "                  .resolve(\"database-telemetry.ndjson\")\n"
        "                  .toString()));\n"
        "    }\n\n"
        "    return new OptionsBuilder()\n",
        "layout harness telemetry JVM properties",
    )
    test_path.write_text(test, encoding="utf-8")


status_path = Path("docs/performance-status.md")
status = status_path.read_text(encoding="utf-8")
old = (
    "- **Physical ceiling:** host, network, database and storage-device throughput. "
    "WAL/fsync and physical database bytes are not yet recorded together, so this ceiling is not known."
)
new = (
    "- **Physical ceiling:** host, network, database and storage-device throughput. "
    "The benchmark telemetry SPI now captures PostgreSQL WAL/I/O and SQL Server log/file-I/O "
    "deltas outside timed invocations for the pack-layout path. This is the first direct evidence "
    "toward that ceiling; broader benchmark coverage and calibrated attribution remain open in #187."
)
if old in status:
    status_path.write_text(status.replace(old, new, 1), encoding="utf-8")
