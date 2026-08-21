#!/usr/bin/env python3
"""Keep pack-layout database credentials out of retained JMH evidence."""

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
benchmark = replace_once(
    benchmark,
    '''import java.io.IOException;
import java.nio.file.Path;
''',
    '''import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
''',
    "benchmark connection-file imports",
)
benchmark = replace_once(
    benchmark,
    '''import java.util.Properties;
import java.util.concurrent.TimeUnit;
''',
    '''import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
''',
    "benchmark logging imports",
)
benchmark = replace_once(
    benchmark,
    '''  public static final String SQL_SERVER_PASSWORD_PROPERTY =
      "jgit.storage.benchmark.sqlserver.password";

  private static final int SHORT_READ_BYTES = 64 * 1024;
''',
    '''  public static final String SQL_SERVER_PASSWORD_PROPERTY =
      "jgit.storage.benchmark.sqlserver.password";
  static final String CONNECTION_PROPERTIES_FILE_PROPERTY =
      "jgit.storage.benchmark.connection-properties-file";

  private static final int SHORT_READ_BYTES = 64 * 1024;
''',
    "ephemeral connection-file property",
)
benchmark = replace_once(
    benchmark,
    '''  public void setupTrial() {
    requireOperation(operation);
''',
    '''  public void setupTrial() {
    suppressConnectionMetadataLogging();
    requireOperation(operation);
''',
    "connection metadata logging suppression",
)
benchmark = replace_once(
    benchmark,
    '''  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }
''',
    '''  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String connectionPropertiesFile =
        System.getProperty(CONNECTION_PROPERTIES_FILE_PROPERTY);
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
    "connection-file property loading",
)
benchmark_path.write_text(benchmark, encoding="utf-8")


test_path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PackStorageLayoutBenchmarkTest.java"
)
tests = test_path.read_text(encoding="utf-8")
tests = replace_once(
    tests,
    '''import java.nio.file.Files;
import java.nio.file.Path;
''',
    '''import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
''',
    "runner credential-file imports",
)
tests = replace_once(
    tests,
    '''import java.util.List;
import java.util.Set;
''',
    '''import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
''',
    "runner credential collections imports",
)
tests = replace_once(
    tests,
    '''    assertTrue(resultCount > 0);
    assertTrue(Files.isRegularFile(resultFile));
    assertTrue(Files.size(resultFile) > 2);
''',
    '''    assertTrue(resultCount > 0);
    assertTrue(Files.isRegularFile(resultFile));
    assertTrue(Files.size(resultFile) > 2);
    assertCredentialFreeEvidence(resultFile.getParent());
''',
    "retained evidence credential assertion",
)
tests = replace_once(
    tests,
    '''  private record Scenario(
''',
    '''  private static void assertCredentialFreeEvidence(Path root) throws IOException {
    List<String> forbidden =
        List.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY + "=",
            "jdbc:postgresql://",
            "jdbc:sqlserver://",
            "Database JDBC URL",
            "Default catalog/schema");
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content = Files.readString(file);
        for (String token : forbidden) {
          assertFalse(
              content.contains(token),
              () -> "Retained evidence " + file + " contains " + token);
        }
      }
    }
  }

  private record Scenario(
''',
    "retained evidence credential scanner",
)
tests = replace_once(
    tests,
    '''    private final MSSQLServerContainer sqlServer;
    private final List<String> jvmArguments;

    private DatabaseTarget(
        PostgreSQLContainer<?> postgresql,
        MSSQLServerContainer sqlServer,
        List<String> jvmArguments) {
      this.postgresql = postgresql;
      this.sqlServer = sqlServer;
      this.jvmArguments = List.copyOf(jvmArguments);
    }

    private static DatabaseTarget local() {
      return new DatabaseTarget(null, null, List.of());
    }
''',
    '''    private final MSSQLServerContainer sqlServer;
    private final Path connectionPropertiesFile;
    private final List<String> jvmArguments;

    private DatabaseTarget(
        PostgreSQLContainer<?> postgresql,
        MSSQLServerContainer sqlServer,
        Path connectionPropertiesFile,
        List<String> jvmArguments) {
      this.postgresql = postgresql;
      this.sqlServer = sqlServer;
      this.connectionPropertiesFile = connectionPropertiesFile;
      this.jvmArguments = List.copyOf(jvmArguments);
    }

    private static DatabaseTarget local() {
      return new DatabaseTarget(null, null, null, List.of());
    }
''',
    "database target credential-file state",
)
tests = replace_once(
    tests,
    '''      return new DatabaseTarget(
          container,
          null,
          List.of(
              RepositoryBackendBenchmarkIT.systemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                  container.getJdbcUrl()),
              RepositoryBackendBenchmarkIT.systemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                  container.getUsername()),
              RepositoryBackendBenchmarkIT.systemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                  container.getPassword())));
''',
    '''      try {
        Path connectionPropertiesFile =
            writeConnectionProperties(
                Map.of(
                    HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                    container.getJdbcUrl(),
                    HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                    container.getUsername(),
                    HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                    container.getPassword()));
        return new DatabaseTarget(
            container,
            null,
            connectionPropertiesFile,
            List.of(
                RepositoryBackendBenchmarkIT.systemProperty(
                    PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
                    connectionPropertiesFile.toString())));
      } catch (RuntimeException failure) {
        container.stop();
        throw failure;
      }
''',
    "PostgreSQL ephemeral credential file",
)
tests = replace_once(
    tests,
    '''      return new DatabaseTarget(
          null,
          container,
          List.of(
              RepositoryBackendBenchmarkIT.systemProperty(
                  PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY,
                  container.getJdbcUrl()),
              RepositoryBackendBenchmarkIT.systemProperty(
                  PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY,
                  container.getUsername()),
              RepositoryBackendBenchmarkIT.systemProperty(
                  PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY,
                  container.getPassword())));
''',
    '''      try {
        Path connectionPropertiesFile =
            writeConnectionProperties(
                Map.of(
                    PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY,
                    container.getJdbcUrl(),
                    PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY,
                    container.getUsername(),
                    PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY,
                    container.getPassword()));
        return new DatabaseTarget(
            null,
            container,
            connectionPropertiesFile,
            List.of(
                RepositoryBackendBenchmarkIT.systemProperty(
                    PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
                    connectionPropertiesFile.toString())));
      } catch (RuntimeException failure) {
        container.stop();
        throw failure;
      }
''',
    "SQL Server ephemeral credential file",
)
tests = replace_once(
    tests,
    '''    private List<String> jvmArguments() {
      return jvmArguments;
    }

    @Override
    public void close() {
      if (postgresql != null) {
        postgresql.stop();
      }
      if (sqlServer != null) {
        sqlServer.stop();
      }
    }
''',
    '''    private static Path writeConnectionProperties(Map<String, String> values) {
      Path target = null;
      try {
        target =
            Files.createTempFile(
                "jgit-storage-benchmark-connection-", ".properties");
        try {
          Files.setPosixFilePermissions(
              target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
          // The CI and supported production benchmark runners use POSIX filesystems.
        }
        Properties properties = new Properties();
        values.forEach(properties::setProperty);
        try (OutputStream output = Files.newOutputStream(target)) {
          properties.store(output, "ephemeral benchmark connection properties");
        }
        return target;
      } catch (IOException failure) {
        if (target != null) {
          try {
            Files.deleteIfExists(target);
          } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
        throw new IllegalStateException(
            "Cannot create temporary benchmark connection properties", failure);
      }
    }

    private List<String> jvmArguments() {
      return jvmArguments;
    }

    @Override
    public void close() {
      RuntimeException stopFailure = null;
      try {
        if (postgresql != null) {
          postgresql.stop();
        }
        if (sqlServer != null) {
          sqlServer.stop();
        }
      } catch (RuntimeException failure) {
        stopFailure = failure;
      }
      try {
        if (connectionPropertiesFile != null) {
          Files.deleteIfExists(connectionPropertiesFile);
        }
      } catch (IOException cleanupFailure) {
        if (stopFailure != null) {
          stopFailure.addSuppressed(cleanupFailure);
        } else {
          throw new IllegalStateException(
              "Cannot delete temporary benchmark connection properties",
              cleanupFailure);
        }
      }
      if (stopFailure != null) {
        throw stopFailure;
      }
    }
''',
    "credential-file creation and cleanup",
)
test_path.write_text(tests, encoding="utf-8")


docs_path = Path("docs/operations/database-native-telemetry.md")
docs = docs_path.read_text(encoding="utf-8")
docs = replace_once(
    docs,
    '''Artifacts do not contain:

- JDBC URLs or host names;
- database names;
- usernames or passwords;
- SQL parameters;
- statement or query text;
- raw JDBC exception messages.
''',
    '''The native telemetry companion JSON does not contain:

- JDBC URLs, database names, usernames or passwords;
- SQL parameters;
- statement or query text;
- raw JDBC exception messages.

Pack-layout Testcontainers credentials are passed to JMH forks through a short-lived owner-readable properties file outside the retained artifact directory. The file path, but not its contents, may appear in JMH VM arguments. The runner deletes the file before evidence is uploaded and scans raw JMH JSON and console output for direct connection properties and JDBC URLs.
''',
    "telemetry and retained-artifact privacy contract",
)
docs_path.write_text(docs, encoding="utf-8")
