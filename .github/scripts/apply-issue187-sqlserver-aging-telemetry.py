#!/usr/bin/env python3
"""Extend repository-aging native telemetry from PostgreSQL to SQL Server."""

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
    '''  private DatabaseTelemetryCollector databaseTelemetryCollector() {
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
''',
    '''  private DatabaseTelemetryCollector databaseTelemetryCollector() {
    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    String databaseBackend = databaseBackend();
    if (!enabled) {
      return DatabaseTelemetryCollectors.disabled(
          databaseBackend, "disabled-by-configuration");
    }
    return switch (databaseBackend) {
      case HibernateRepositoryBenchmark.HSQLDB ->
          DatabaseTelemetryCollectors.disabled(
              HibernateRepositoryBenchmark.HSQLDB, "unsupported-backend");
      case HibernateRepositoryBenchmark.POSTGRESQL ->
          DatabaseTelemetryCollectors.create(
              HibernateRepositoryBenchmark.POSTGRESQL,
              true,
              requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
              requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
              requiredProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
      case PackStorageLayoutBenchmark.SQL_SERVER ->
          DatabaseTelemetryCollectors.create(
              PackStorageLayoutBenchmark.SQL_SERVER,
              true,
              requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY),
              requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY),
              requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY));
      default ->
          throw new IllegalArgumentException(
              "Unsupported aging telemetry backend " + databaseBackend);
    };
  }

  private String databaseBackend() {
    return switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> HibernateRepositoryBenchmark.HSQLDB;
      case HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI ->
          HibernateRepositoryBenchmark.POSTGRESQL;
      case PackStorageLayoutBenchmark.SQL_SERVER -> PackStorageLayoutBenchmark.SQL_SERVER;
      default -> throw new IllegalArgumentException("Unsupported aging backend " + backend);
    };
  }
''',
    "repository-aging telemetry backend selection",
)
benchmark = replace_once(
    benchmark,
    '''      }
      default -> throw new IllegalArgumentException("Unsupported aging backend " + backend);
    }
''',
    '''      }
      case PackStorageLayoutBenchmark.SQL_SERVER -> {
        properties.put(
            "hibernate.connection.url",
            requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY));
        properties.put(
            "hibernate.connection.username",
            requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY));
        properties.put(
            "hibernate.connection.password",
            requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY));
        properties.put(
            "hibernate.connection.driver_class",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
        properties.put("hibernate.connection.pool_size", "4");
      }
      default -> throw new IllegalArgumentException("Unsupported aging backend " + backend);
    }
''',
    "SQL Server repository-aging Hibernate configuration",
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
    "import org.testcontainers.containers.PostgreSQLContainer;\n",
    "import org.testcontainers.containers.PostgreSQLContainer;\n"
    "import org.testcontainers.mssqlserver.MSSQLServerContainer;\n",
    "SQL Server Testcontainers import",
)
runner = replace_once(
    runner,
    '''  private static final String REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY =
      "jgit.storage.benchmark.repository-aging.native-smoke";
''',
    '''  private static final String REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY =
      "jgit.storage.benchmark.repository-aging.native-smoke";
  private static final String REPOSITORY_AGING_DATABASE_BACKEND_PROPERTY =
      "jgit.storage.benchmark.repository-aging.database-backend";
''',
    "repository-aging database-backend property",
)
runner = replace_once(
    runner,
    '''    boolean repositoryAgingNativeSmoke =
        repositoryAging
            && Boolean.getBoolean(REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY);
    boolean telemetryEnabled =
''',
    '''    boolean repositoryAgingNativeSmoke =
        repositoryAging
            && Boolean.getBoolean(REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY);
    String repositoryAgingDatabaseBackend =
        repositoryAgingNativeSmoke
            ? repositoryAgingDatabaseBackend()
            : HibernateRepositoryBenchmark.POSTGRESQL;
    boolean telemetryEnabled =
''',
    "repository-aging selected native backend",
)
runner = replace_once(
    runner,
    '''    Path connectionPropertiesFile = null;
    List<String> jvmArguments = new ArrayList<>();
    jvmArguments.add("-Xms1g");
    jvmArguments.add(full ? "-Xmx3g" : "-Xmx1536m");
    if (writeQueue || repositoryAging) {
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

    configure(
        builder,
        investigation,
        full,
        deployment,
        threads,
        repositoryAgingNativeSmoke);
    Collection<RunResult> results;
    try {
      results = new Runner(builder.build()).run();
    } finally {
      if (connectionPropertiesFile != null) {
        Files.deleteIfExists(connectionPropertiesFile);
      }
    }
''',
    '''    Path connectionPropertiesFile = null;
    MSSQLServerContainer sqlServer = null;
    Collection<RunResult> results;
    try {
      if (repositoryAgingNativeSmoke
          && PackStorageLayoutBenchmark.SQL_SERVER.equals(
              repositoryAgingDatabaseBackend)) {
        sqlServer =
            new MSSQLServerContainer(
                    "mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
                .acceptLicense();
        sqlServer.start();
      }

      List<String> jvmArguments = new ArrayList<>();
      jvmArguments.add("-Xms1g");
      jvmArguments.add(full ? "-Xmx3g" : "-Xmx1536m");
      if (writeQueue || repositoryAging) {
        connectionPropertiesFile =
            writeConnectionProperties(repositoryAgingDatabaseBackend, sqlServer);
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

      configure(
          builder,
          investigation,
          full,
          deployment,
          threads,
          repositoryAgingNativeSmoke,
          repositoryAgingDatabaseBackend);
      results = new Runner(builder.build()).run();
    } finally {
      if (connectionPropertiesFile != null) {
        Files.deleteIfExists(connectionPropertiesFile);
      }
      if (sqlServer != null) {
        sqlServer.stop();
      }
    }
''',
    "conditional SQL Server benchmark target lifecycle",
)
runner = replace_once(
    runner,
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
    '''  private static String repositoryAgingDatabaseBackend() {
    String value =
        System.getProperty(
            REPOSITORY_AGING_DATABASE_BACKEND_PROPERTY,
            HibernateRepositoryBenchmark.POSTGRESQL);
    if (!HibernateRepositoryBenchmark.POSTGRESQL.equals(value)
        && !PackStorageLayoutBenchmark.SQL_SERVER.equals(value)) {
      throw new IllegalArgumentException(
          REPOSITORY_AGING_DATABASE_BACKEND_PROPERTY
              + " must be postgresql or sqlserver but was "
              + value);
    }
    return value;
  }

  private static Path writeConnectionProperties(
      String databaseBackend, MSSQLServerContainer sqlServer) throws IOException {
    Map<String, String> connectionProperties =
        switch (databaseBackend) {
          case HibernateRepositoryBenchmark.POSTGRESQL ->
              Map.of(
                  HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                  POSTGRESQL.getJdbcUrl(),
                  HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                  POSTGRESQL.getUsername(),
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                  POSTGRESQL.getPassword());
          case PackStorageLayoutBenchmark.SQL_SERVER -> {
            if (sqlServer == null || !sqlServer.isRunning()) {
              throw new IllegalStateException("SQL Server benchmark target is not running");
            }
            yield Map.of(
                PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY,
                sqlServer.getJdbcUrl(),
                PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY,
                sqlServer.getUsername(),
                PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY,
                sqlServer.getPassword());
          }
          default ->
              throw new IllegalArgumentException(
                  "Unsupported benchmark connection backend " + databaseBackend);
        };

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
      connectionProperties.forEach(properties::setProperty);
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
    "backend-specific temporary connection properties",
)
runner = replace_once(
    runner,
    '''            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            "jdbc:postgresql://",
            "Database JDBC URL",
''',
    '''            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY + "=",
            "jdbc:postgresql://",
            "jdbc:sqlserver://",
            "Database JDBC URL",
''',
    "cross-database credential redaction tokens",
)
runner = replace_once(
    runner,
    '''      int threads,
      boolean repositoryAgingNativeSmoke) {
''',
    '''      int threads,
      boolean repositoryAgingNativeSmoke,
      String repositoryAgingDatabaseBackend) {
''',
    "configure selected database-backend argument",
)
runner = replace_once(
    runner,
    '''                  repositoryAgingNativeSmoke
                      ? new String[] {HibernateRepositoryBenchmark.POSTGRESQL}
''',
    '''                  repositoryAgingNativeSmoke
                      ? new String[] {repositoryAgingDatabaseBackend}
''',
    "native repository-aging backend parameter",
)
runner_path.write_text(runner, encoding="utf-8")


doc_path = Path("docs/operations/database-native-telemetry.md")
doc = doc_path.read_text(encoding="utf-8")
doc = replace_once(
    doc,
    '''The focused PostgreSQL aging sub-run separates three boundaries for every selected JMH coordinate:

1. deterministic incremental fixture publication;
2. the selected maintenance action (`none`, compact-only or read-optimized);
3. the later measured lookup/traversal/reopen iteration.

This prevents repack WAL and I/O from being misattributed to the read that benefits from it. The initial bounded profile uses ten pushes and representative oldest-object, clone-style and reopen lookups. It proves attribution and artifact contracts; the 32/100/300/1,000-push production matrix and SQL Server aging telemetry remain open.
''',
    '''The focused aging matrix separates three boundaries for every selected JMH coordinate on PostgreSQL and SQL Server:

1. deterministic incremental fixture publication;
2. the selected maintenance action (`none`, compact-only or read-optimized);
3. the later measured lookup/traversal/reopen iteration.

This prevents repack WAL/log and file-I/O work from being misattributed to the read that benefits from it. The initial bounded profile uses ten pushes and representative oldest-object, clone-style and reopen lookups. PostgreSQL requires immediate WAL insert-LSN evidence; SQL Server requires transaction-log file-write evidence and retains data/log I/O and server-scoped wait categories from its isolated Testcontainers instance. This proves cross-database phase attribution and artifact contracts; the 32/100/300/1,000-push production matrix remains open.
''',
    "cross-database repository-aging interpretation",
)
doc = replace_once(
    doc,
    "1. full-scale repository aging/MIDX/repack and SQL Server;\n",
    "1. full-scale repository aging/MIDX/repack;\n",
    "remaining full-scale aging scope",
)
doc_path.write_text(doc, encoding="utf-8")


status_path = Path("docs/performance-status.md")
status = status_path.read_text(encoding="utf-8")
status = replace_once(
    status,
    "and separated PostgreSQL fixture-build, maintenance and measured-read phases for a bounded repository-aging profile. Full-scale aging/SQL Server, atomic receiver batches, Search paths and calibrated attribution remain open in #187.",
    "and separated PostgreSQL and SQL Server fixture-build, maintenance and measured-read phases for a bounded repository-aging profile. Full-scale aging, atomic receiver batches, Search paths and calibrated attribution remain open in #187.",
    "performance-status cross-database aging statement",
)
status_path.write_text(status, encoding="utf-8")
