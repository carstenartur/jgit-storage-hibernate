/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** Manual-only paired study; its fixed stopping rule is declared before any measured scores. */
class PackPublicationPairedBenchmarkIT {

  private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  private static final String CONNECTION_FILE =
      "jgit.storage.benchmark.connection-properties-file";
  private static final int PROXY_DATA_PORT = 8666;

  @Test
  @Timeout(value = 145, unit = TimeUnit.MINUTES)
  void retainsPreregisteredPairedForks() throws Exception {
    Cell cell = Cell.parse(System.getProperty("jgit.storage.benchmark.publication.cell"));
    String source = sourceProperty("jgit.storage.benchmark.source");
    String tree = sourceProperty("jgit.storage.benchmark.sourceTree");
    Path root =
        Path.of(System.getProperty("benchmark.resultDirectory", "target/publication-paired"))
            .toAbsolutePath();
    Files.createDirectories(root);
    if (Files.exists(root.resolve("protocol.json"))) {
      throw new IllegalStateException("Refusing to overwrite a prior paired study");
    }
    Files.createDirectories(root.resolve("raw"));
    writeJson(root.resolve("protocol.json"), protocol(cell, source, tree));

    Path databaseDirectory = Files.createTempDirectory("jgit-paired-postgresql-");
    String fileStoreType = Files.getFileStore(databaseDirectory).type();
    if (fileStoreType.equalsIgnoreCase("tmpfs") || fileStoreType.equalsIgnoreCase("ramfs")) {
      throw new IllegalStateException("This study requires a disk-backed runner filesystem");
    }
    List<Map<String, Object>> trials = new ArrayList<>();
    Path connectionFile = null;
    try (Network network = Network.newNetwork();
        PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.10-alpine")
                .withDatabaseName("publication_paired")
                .withUsername("benchmark")
                .withPassword("benchmark")
                .withNetwork(network)
                .withNetworkAliases("publication-paired-postgresql")
                .withFileSystemBind(
                    databaseDirectory.toString(), "/var/lib/postgresql/data", BindMode.READ_WRITE)
                .withCommand(
                    "postgres",
                    "-c", "fsync=on",
                    "-c", "synchronous_commit=on",
                    "-c", "full_page_writes=on",
                    "-c", "shared_buffers=128MB",
                    "-c", "max_connections=100",
                    "-c", "max_wal_size=1GB");
        ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(network)) {
      postgres.start();
      toxiproxy.start();
      ToxiproxyClient client =
          new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
      Proxy proxy =
          client.createProxy(
              "publication-paired",
              "0.0.0.0:" + PROXY_DATA_PORT,
              "publication-paired-postgresql:5432");
      int upstream = cell.rttMillis() / 2;
      int downstream = cell.rttMillis() - upstream;
      if (upstream > 0) {
        proxy.toxics().latency("paired-upstream", ToxicDirection.UPSTREAM, upstream);
      }
      if (downstream > 0) {
        proxy.toxics().latency("paired-downstream", ToxicDirection.DOWNSTREAM, downstream);
      }
      String url =
          "jdbc:postgresql://"
              + toxiproxy.getHost()
              + ":"
              + toxiproxy.getMappedPort(PROXY_DATA_PORT)
              + "/"
              + postgres.getDatabaseName();
      connectionFile = writeConnectionFile(url, postgres);
      try (Connection observer =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        Map<String, Object> environment =
            environment(observer, cell, source, tree, fileStoreType, postgres);
        writeJson(root.resolve("environment.json"), environment);
        List<Double> ratios = new ArrayList<>();
        int maximum = cell.shared() ? 24 : 96;
        for (int pair = 1; pair <= maximum; pair++) {
          Map<String, Double> scores = new LinkedHashMap<>();
          for (int position = 0; position < 2; position++) {
            String policy = PublicationPairing.policyAt(pair, position);
            String base = "pair-%02d-%s".formatted(pair, policy);
            Path resultFile = root.resolve("raw/" + base + ".json");
            Path outputFile = root.resolve("raw/" + base + ".log");
            JsonElement before = checkpointState(observer);
            Instant started = Instant.now();
            Collection<RunResult> results =
                new Runner(options(cell, policy, connectionFile, resultFile, outputFile)).run();
            Instant completed = Instant.now();
            if (results.size() != 1) {
              throw new IllegalStateException("Expected one result from one fresh JMH fork");
            }
            double score = results.iterator().next().getPrimaryResult().getScore();
            if (!Double.isFinite(score) || score <= 0) {
              throw new IllegalStateException("Fork did not return finite positive throughput");
            }
            scores.put(policy, score);
            Map<String, Object> trial = new LinkedHashMap<>();
            trial.put("pair", pair);
            trial.put("position", position);
            trial.put("policy", policy);
            trial.put("sourceCommit", source);
            trial.put("result", "raw/" + base + ".json");
            trial.put("output", "raw/" + base + ".log");
            trial.put("startedAt", started.toString());
            trial.put("completedAt", completed.toString());
            trial.put("checkpointBefore", before);
            trial.put("checkpointAfter", checkpointState(observer));
            trials.add(trial);
            writeJson(root.resolve("trials.json"), trials);
            System.out.printf(
                "Completed %s pair %d position %d policy %s%n",
                cell.id(), pair, position, policy);
          }
          ratios.add(scores.get("contention-aware") / scores.get("size"));
          if (!cell.shared()
              && pair == 24
              && PublicationPairing.lowerBoundPercent(ratios) > -5.0) {
            break;
          }
        }
        environment.put("finalRttSamplesMillis", calibrate(observer, cell.rttMillis()));
        environment.put("completedAt", Instant.now().toString());
        writeJson(root.resolve("environment.json"), environment);
      }
    } finally {
      // Owned host database directories are ephemeral runner data, never artifacts.
      // Docker has closed before this block; PostgreSQL may have changed their POSIX owner.
      if (connectionFile != null) {
        Files.deleteIfExists(connectionFile);
      }
      writeJson(root.resolve("trials.json"), trials);
    }
  }

  static Options options(
      Cell cell, String policy, Path connectionFile, Path resultFile, Path outputFile) {
    String method =
        cell.shared() ? "publishToSameRepository" : "publishToDifferentRepositories";
    return new OptionsBuilder()
        .include(Pattern.quote(ConcurrentPublicationBenchmark.class.getName() + "." + method) + "$")
        .mode(Mode.Throughput)
        .threads(cell.writers())
        .param("backend", "postgresql-hikari")
        .param("writeMode", "stateful")
        .param("payloadKiB", Integer.toString(cell.payloadKiB()))
        .param("payloadMiB", "0")
        .param("publicationPolicy", policy)
        .param("deployment", "paired-" + cell.id())
        .warmupIterations(2)
        .warmupTime(TimeValue.seconds(3))
        .measurementIterations(3)
        .measurementTime(TimeValue.seconds(5))
        .forks(1)
        .timeout(TimeValue.seconds(120))
        .shouldFailOnError(true)
        .addProfiler(GCProfiler.class)
        .addProfiler(PackPublicationProfiler.class)
        .resultFormat(ResultFormatType.JSON)
        .result(resultFile.toString())
        .output(outputFile.toString())
        .jvmArgs("-Xms256m", "-Xmx1024m", "-D" + CONNECTION_FILE + "=" + connectionFile)
        .build();
  }

  static Map<String, Object> protocol(Cell cell, String source, String tree) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schema", "jgit-publication-paired/v1");
    values.put("cell", cell.id());
    values.put("sourceCommit", source);
    values.put("sourceTree", tree);
    values.put("pairStages", cell.shared() ? List.of(24) : List.of(24, 96));
    values.put("warmupIterations", 2);
    values.put("warmupSeconds", 3);
    values.put("measurementIterations", 3);
    values.put("measurementSeconds", 5);
    values.put("policies", List.of("size", "contention-aware"));
    values.put("order", "odd-size-first-even-contention-first");
    values.put("marginPercent", -5.0);
    values.put("heapMiB", 1024);
    values.put("initialHeapMiB", 256);
    values.put("jdkMajor", 21);
    values.put("jmhVersion", "1.37");
    values.put("layout", cell.shared() ? "shared" : "independent");
    values.put("writers", cell.writers());
    values.put("payloadKiB", cell.payloadKiB());
    values.put("requestedRttMillis", cell.rttMillis());
    values.put("backend", "postgresql-hikari");
    values.put("writeMode", "stateful");
    return values;
  }

  static Map<String, Object> environment(
      Connection connection,
      Cell cell,
      String source,
      String tree,
      String fileStoreType,
      PostgreSQLContainer<?> postgres)
      throws Exception {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("sourceCommit", source);
    values.put("sourceTree", tree);
    values.put("startedAt", Instant.now().toString());
    values.put("javaVersion", System.getProperty("java.version"));
    values.put("javaVm", System.getProperty("java.vm.name"));
    values.put("javaVmVersion", System.getProperty("java.vm.version"));
    values.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    values.put("osName", System.getProperty("os.name"));
    values.put("osArch", System.getProperty("os.arch"));
    values.put("osVersion", System.getProperty("os.version"));
    values.put("cpuMax", readIfPresent(Path.of("/sys/fs/cgroup/cpu.max")));
    values.put("memoryMax", readIfPresent(Path.of("/sys/fs/cgroup/memory.max")));
    values.put("cpuModel", readFieldIfPresent(Path.of("/proc/cpuinfo"), "model name"));
    values.put(
        "cpuAffinity", readFieldIfPresent(Path.of("/proc/self/status"), "Cpus_allowed_list"));
    values.put("databaseMajorVersion", connection.getMetaData().getDatabaseMajorVersion());
    values.put("databaseVersion", queryString(connection, "select version()"));
    values.put("databaseImage", postgres.getDockerImageName());
    values.put("databaseImageId", postgres.getCurrentContainerInfo().getImageId());
    values.put("fsync", queryString(connection, "show fsync"));
    values.put("synchronousCommit", queryString(connection, "show synchronous_commit"));
    values.put("fullPageWrites", queryString(connection, "show full_page_writes"));
    values.put("sharedBuffers", queryString(connection, "show shared_buffers"));
    values.put("maxWalSize", queryString(connection, "show max_wal_size"));
    values.put("storage", "host-bind");
    values.put("fileStoreType", fileStoreType);
    values.put("rttSamplesMillis", calibrate(connection, cell.rttMillis()));
    if (!"on".equals(values.get("fsync"))
        || !"on".equals(values.get("synchronousCommit"))
        || !"on".equals(values.get("fullPageWrites"))) {
      throw new IllegalStateException("The actual PostgreSQL settings are not durable");
    }
    return values;
  }

  static List<Double> calibrate(Connection connection, int requested) throws Exception {
    List<Double> samples = new ArrayList<>();
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      for (int index = -5; index < 20; index++) {
        long start = System.nanoTime();
        try (ResultSet result = statement.executeQuery("select 1")) {
          if (!result.next() || result.getInt(1) != 1) {
            throw new IllegalStateException("RTT calibration query returned no result");
          }
        }
        if (index >= 0) {
          samples.add((System.nanoTime() - start) / 1_000_000.0);
        }
      }
    }
    List<Double> sorted = samples.stream().sorted().toList();
    double median = (sorted.get(9) + sorted.get(10)) / 2;
    if (median < requested || median > requested + 25) {
      throw new IllegalStateException("The measured RTT is outside the declared range");
    }
    return samples;
  }

  static JsonElement checkpointState(Connection connection) throws Exception {
    return JsonParser.parseString(
        queryString(connection, "select to_jsonb(s)::text from pg_stat_checkpointer s"));
  }

  private static String queryString(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (ResultSet result = statement.executeQuery(sql)) {
        if (!result.next()) {
          throw new IllegalStateException("Required PostgreSQL metadata is missing");
        }
        return result.getString(1);
      }
    }
  }

  static Path writeConnectionFile(String url, PostgreSQLContainer<?> postgres)
      throws Exception {
    Path path =
        Files.createTempFile(
            "jgit-paired-connection-", ".properties",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Properties properties = new Properties();
    properties.setProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY, url);
    properties.setProperty(
        HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY, postgres.getUsername());
    properties.setProperty(
        HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY, postgres.getPassword());
    try (OutputStream output = Files.newOutputStream(path)) {
      properties.store(output, "Ephemeral paired benchmark connection");
    } catch (Throwable failure) {
      Files.deleteIfExists(path);
      throw failure;
    }
    return path;
  }

  static String sourceProperty(String name) {
    String value = System.getProperty(name, "");
    if (!value.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException(name + " must be an immutable 40-character Git SHA");
    }
    return value;
  }

  private static String readIfPresent(Path path) throws Exception {
    return Files.isRegularFile(path) ? Files.readString(path).trim() : "unavailable";
  }

  private static String readFieldIfPresent(Path path, String field) throws Exception {
    if (!Files.isRegularFile(path)) return "unavailable";
    try (var lines = Files.lines(path)) {
      return lines
          .map(line -> line.split(":", 2))
          .filter(parts -> parts.length == 2 && parts[0].trim().equals(field))
          .map(parts -> parts[1].trim())
          .filter(value -> !value.isEmpty())
          .findFirst()
          .orElse("unavailable");
    }
  }

  static void writeJson(Path path, Object value) throws Exception {
    Files.writeString(path, JSON.toJson(value) + "\n", StandardCharsets.UTF_8);
  }

  record Cell(String id, boolean shared, int writers, int payloadKiB, int rttMillis) {
    static Cell parse(String id) {
      if (id == null) {
        throw new IllegalArgumentException("Select one explicit publication cell");
      }
      return switch (id) {
        case "independent-1w-0ms" -> new Cell(id, false, 1, 256, 0);
        case "independent-1w-50ms" -> new Cell(id, false, 1, 256, 50);
        case "independent-16w-0ms" -> new Cell(id, false, 16, 256, 0);
        case "independent-16w-50ms" -> new Cell(id, false, 16, 256, 50);
        case "shared-8w-5ms" -> new Cell(id, true, 8, 768, 5);
        default -> throw new IllegalArgumentException("Unknown preregistered publication cell: " + id);
      };
    }
  }
}
