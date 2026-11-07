/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Opt-in comparison of remote indexing with and without transactionally persisted outbox events.
 */
@EnabledIfSystemProperty(named = "search.outbox.run", matches = "true")
class SearchRemoteOutboxIT {

  private static final String POSTGRES_IMAGE = "postgres:17.10-alpine";
  private static final String SEARCH_IMAGE = "opensearchproject/opensearch:2.19.5";
  private static final String EVENT_TABLE = "experiment_outbox_event";
  private static final String AGENT_TABLE = "experiment_outbox_agent";
  private static final String REPLAY_REPOSITORY = "restart/replay";
  private static final String REPLAY_TEXT = "durableoutboxreplay";
  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final String RESPONSE = "OUTBOX_RESULT ";
  private static final String HEADER =
      "mode,instances,phase,documents,ack_ms,ack_to_visible_ms,boot_to_visible_ms,"
          + "indexed_documents,queued_before_restart,queued_after_visible,event_rows_inserted,"
          + "event_rows_deleted,outbox_relations_bytes,postgres_network_bytes,search_network_bytes";

  @TempDir Path scratch;

  @Test
  void compareSharedRemoteBackendAndVerifyOutboxReplay() throws Exception {
    int instances = Integer.getInteger("search.outbox.instances", 1);
    int repetitions = Integer.getInteger("search.outbox.repetitions", 3);
    if (!List.of(1, 2, 4).contains(instances) || repetitions < 1 || repetitions > 20) {
      throw new IllegalArgumentException(
          "Use 1, 2 or 4 instances and between 1 and 20 repetitions");
    }
    Path output =
        Path.of(System.getProperty("search.outbox.output", "target/search-remote-outbox"));
    Files.createDirectories(output);
    Files.writeString(
        output.resolve("environment.properties"),
        "postgres="
            + POSTGRES_IMAGE
            + "\nsearch="
            + SEARCH_IMAGE
            + "\njava="
            + System.getProperty("java.runtime.version")
            + "\nsource="
            + System.getProperty("search.outbox.source", "local checkout")
            + "\ninstances="
            + instances
            + "\nrepetitions="
            + repetitions
            + "\n");
    try (var postgres =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("search_experiment")
                .withUsername("experiment")
                .withPassword("experiment");
        var openSearch =
            new GenericContainer<>(DockerImageName.parse(SEARCH_IMAGE))
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("node.store.allow_mmap", "false")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m -XX:ActiveProcessorCount=2")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3))) {
      Startables.deepStart(postgres, openSearch).join();
      var cluster =
          new Cluster(
              postgres.getJdbcUrl(),
              postgres.getUsername(),
              postgres.getPassword(),
              "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200),
              () -> new Network(networkBytes(postgres), networkBytes(openSearch)));
      run(cluster, instances, repetitions, output);
    }
  }

  private void run(Cluster cluster, int instances, int repetitions, Path output) throws Exception {
    Path logs = Files.createDirectories(output.resolve("process-logs"));
    try (Writer csv = Files.newBufferedWriter(output.resolve("outbox.csv"))) {
      csv.write(HEADER + "\n");
      for (Mode mode : Mode.values()) {
        measure(cluster, mode, instances, repetitions, logs, csv);
      }
    }
  }

  private void measure(
      Cluster cluster, Mode mode, int instances, int repetitions, Path logs, Writer csv)
      throws Exception {
    Properties initialize = cluster.settings(mode, false);
    initialize.setProperty("hibernate.hbm2ddl.auto", "create");
    initialize.setProperty("hibernate.search.schema_management.strategy", "drop-and-create");
    try (var connection =
            DriverManager.getConnection(cluster.jdbcUrl(), cluster.user(), cluster.password());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + mode.schema);
    }
    try (var factory = open(initialize)) {
      assertEquals(0, hitCount(factory));
    }

    int total = 0;
    Properties regular = cluster.settings(mode, true);
    try (var applications =
        Applications.start(regular, instances, scratch, logs, mode.schema + "-live")) {
      if (mode == Mode.OUTBOX) {
        awaitCount("registered processors", instances, () -> sqlCount(regular, AGENT_TABLE));
      }
      for (int repetition = 0; repetition < repetitions; repetition++) {
        for (int documents : List.of(50, 500)) {
          Network before = cluster.network().read();
          long start = System.nanoTime();
          applications.publish(total, documents);
          long acknowledged = System.nanoTime();
          total += documents;
          awaitCount("visible documents", total, applications::hits);
          long visible = System.nanoTime();
          Network network = cluster.network().read().minus(before);
          if (mode == Mode.OUTBOX) {
            awaitCount("drained outbox", 0, () -> sqlCount(regular, EVENT_TABLE));
          }
          write(
              csv,
              mode,
              instances,
              "burst",
              documents,
              acknowledged - start,
              visible - acknowledged,
              null,
              total,
              0,
              outboxStats(regular, mode),
              network);
        }
      }
    }

    int backlog = 0;
    if (mode == Mode.OUTBOX) {
      Properties paused = cluster.settings(mode, false);
      try (var producer = new Application(paused, scratch, logs, "outbox-paused-producer")) {
        producer.await("ready");
        producer.send("rollback " + total + " 50");
        producer.await("ok");
        assertEquals(total, sqlCount(paused, "git_commit_index"));
        assertEquals(0, sqlCount(paused, EVENT_TABLE));
        producer.send("replay " + total + " 50");
        producer.await("ok");
        backlog = 50;
        assertEquals(total + backlog, sqlCount(paused, "git_commit_index"));
        assertEquals(backlog, sqlCount(paused, EVENT_TABLE));
        assertEquals(total, producer.hits());
        producer.kill();
        assertEquals(backlog, sqlCount(paused, EVENT_TABLE));
      }
    }

    Network before = cluster.network().read();
    long restart = System.nanoTime();
    try (var applications =
        Applications.start(regular, instances, scratch, logs, mode.schema + "-restart")) {
      int expected = total + backlog;
      awaitCount("visible documents after restarting JVMs", expected, applications::hits);
      long visible = System.nanoTime();
      assertEquals(expected, sqlCount(regular, "git_commit_index"));
      if (mode == Mode.OUTBOX) {
        awaitCount(
            "registered replacement processors", instances, () -> sqlCount(regular, AGENT_TABLE));
        awaitCount("replayed outbox", 0, () -> sqlCount(regular, EVENT_TABLE));
        assertEquals(backlog, applications.replayHits());
      }
      write(
          csv,
          mode,
          instances,
          "restart",
          backlog,
          null,
          null,
          visible - restart,
          expected,
          backlog,
          outboxStats(regular, mode),
          cluster.network().read().minus(before));
    }
  }

  private static SessionFactory open(Properties settings) {
    return new Configuration()
        .addAnnotatedClass(GitCommitIndex.class)
        .addProperties(settings)
        .buildSessionFactory();
  }

  private static long hitCount(SessionFactory factory) {
    try (var session = factory.openSession()) {
      return Search.session(session)
          .search(GitCommitIndex.class)
          .where(f -> f.matchAll())
          .fetchTotalHitCount();
    }
  }

  private static java.sql.Connection connect(Properties properties) throws java.sql.SQLException {
    return DriverManager.getConnection(
        properties.getProperty("hibernate.connection.url"),
        properties.getProperty("hibernate.connection.username"),
        properties.getProperty("hibernate.connection.password"));
  }

  private static long sqlCount(Properties settings, String table) throws java.sql.SQLException {
    try (var connection = connect(settings);
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static OutboxStats outboxStats(Properties settings, Mode mode)
      throws java.sql.SQLException {
    if (mode == Mode.DIRECT) {
      return new OutboxStats(0, 0, 0, 0);
    }
    try (var connection = connect(settings);
        var statement =
            connection.prepareStatement(
                """
                SELECT coalesce(sum(n_tup_ins) FILTER (WHERE relname = ?), 0),
                       coalesce(sum(n_tup_del) FILTER (WHERE relname = ?), 0),
                       coalesce(sum(pg_total_relation_size(relid)), 0)
                  FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname IN (?, ?)
                """)) {
      statement.setString(1, EVENT_TABLE);
      statement.setString(2, EVENT_TABLE);
      statement.setString(3, EVENT_TABLE);
      statement.setString(4, AGENT_TABLE);
      try (var result = statement.executeQuery()) {
        result.next();
        return new OutboxStats(
            sqlCount(settings, EVENT_TABLE),
            result.getLong(1),
            result.getLong(2),
            result.getLong(3));
      }
    }
  }

  private static long networkBytes(GenericContainer<?> container) throws Exception {
    var result =
        container.execInContainer(
            "cat",
            "/sys/class/net/eth0/statistics/rx_bytes",
            "/sys/class/net/eth0/statistics/tx_bytes");
    if (result.getExitCode() != 0) {
      throw new IOException("Unable to read container network counters: " + result.getStderr());
    }
    String[] counters = result.getStdout().strip().split("\\s+");
    return Long.parseLong(counters[0]) + Long.parseLong(counters[1]);
  }

  private static void awaitCount(String label, long expected, CountReader reader) throws Exception {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    long actual = reader.read();
    while (actual != expected && System.nanoTime() < deadline) {
      Thread.sleep(50);
      actual = reader.read();
    }
    assertEquals(expected, actual, label);
  }

  private static void write(
      Writer csv,
      Mode mode,
      int instances,
      String phase,
      int documents,
      Long acknowledge,
      Long visibility,
      Long restart,
      int indexed,
      int backlog,
      OutboxStats stats,
      Network network)
      throws IOException {
    String row =
        String.format(
            Locale.ROOT,
            "%s,%d,%s,%d,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d",
            mode.id,
            instances,
            phase,
            documents,
            milliseconds(acknowledge),
            milliseconds(visibility),
            milliseconds(restart),
            indexed,
            backlog,
            stats.pending(),
            stats.inserted(),
            stats.deleted(),
            stats.bytes(),
            network.postgres(),
            network.search());
    csv.write(row + "\n");
    csv.flush();
    System.out.println("Search remote topology " + row);
  }

  private static String milliseconds(Long nanos) {
    return nanos == null ? "" : String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
  }

  private enum Mode {
    DIRECT("remote-direct-write-sync", "direct_experiment"),
    OUTBOX("remote-outbox-eventual", "outbox_experiment");

    private final String id;
    private final String schema;

    Mode(String id, String schema) {
      this.id = id;
      this.schema = schema;
    }
  }

  @FunctionalInterface
  private interface CountReader {
    long read() throws Exception;
  }

  @FunctionalInterface
  private interface NetworkReader {
    Network read() throws Exception;
  }

  private record Network(long postgres, long search) {
    Network minus(Network before) {
      return new Network(postgres - before.postgres, search - before.search);
    }
  }

  private record OutboxStats(long pending, long inserted, long deleted, long bytes) {}

  private record Cluster(
      String jdbcUrl, String user, String password, String uri, NetworkReader network) {
    Properties settings(Mode mode, boolean processEvents) {
      Properties settings = new Properties();
      settings.setProperty(
          "hibernate.connection.url",
          jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + mode.schema);
      settings.setProperty("hibernate.connection.username", user);
      settings.setProperty("hibernate.connection.password", password);
      settings.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
      settings.setProperty("hibernate.connection.pool_size", "6");
      settings.setProperty("hibernate.hbm2ddl.auto", "validate");
      settings.setProperty("hibernate.jdbc.batch_size", "250");
      settings.setProperty("hibernate.search.backend.type", "elasticsearch");
      settings.setProperty("hibernate.search.backend.uris", uri);
      settings.setProperty("hibernate.search.schema_management.strategy", "validate");
      if (mode == Mode.OUTBOX) {
        settings.setProperty("hibernate.search.coordination.strategy", "outbox-polling");
        settings.setProperty(
            "hibernate.search.coordination.event_processor.enabled", String.valueOf(processEvents));
        settings.setProperty(
            "hibernate.search.coordination.event_processor.polling_interval", "100");
        settings.setProperty(
            "hibernate.search.coordination.entity.mapping.outboxevent.table", EVENT_TABLE);
        settings.setProperty(
            "hibernate.search.coordination.entity.mapping.agent.table", AGENT_TABLE);
      } else {
        settings.setProperty(
            "hibernate.search.indexing.plan.synchronization.strategy", "write-sync");
      }
      return settings;
    }
  }

  private static final class Applications implements AutoCloseable {
    private final List<Application> nodes = new ArrayList<>();

    static Applications start(Properties settings, int count, Path scratch, Path logs, String name)
        throws Exception {
      var applications = new Applications();
      try {
        for (int i = 0; i < count; i++) {
          applications.nodes.add(new Application(settings, scratch, logs, name + "-" + i));
        }
        for (Application node : applications.nodes) {
          node.await("ready");
        }
        return applications;
      } catch (Exception | AssertionError failure) {
        applications.close();
        throw failure;
      }
    }

    void publish(int first, int documents) throws Exception {
      for (int i = 0; i < nodes.size(); i++) {
        int end = documents * (i + 1) / nodes.size();
        int start = documents * i / nodes.size();
        nodes.get(i).send("write " + (first + start) + " " + (end - start));
      }
      for (Application node : nodes) {
        node.await("ok");
      }
    }

    long hits() throws Exception {
      return nodes.getFirst().hits();
    }

    long replayHits() throws Exception {
      var node = nodes.getLast();
      node.send("replayhits");
      return Long.parseLong(node.reply());
    }

    @Override
    public void close() {
      nodes.forEach(Application::close);
    }
  }

  private static final class Application implements AutoCloseable {
    private final Process process;
    private final Path log;
    private final BufferedReader responses;
    private final BufferedWriter commands;

    Application(Properties settings, Path scratch, Path logs, String name) throws IOException {
      Path config = Files.createTempFile(scratch, name, ".properties");
      try (Writer writer = Files.newBufferedWriter(config)) {
        settings.store(writer, "Disposable benchmark settings");
      }
      log = logs.resolve(name + ".log");
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      process =
          new ProcessBuilder(
                  java,
                  "-Xmx384m",
                  "-XX:ActiveProcessorCount=2",
                  "-cp",
                  classpath,
                  Worker.class.getName(),
                  config.toString())
              .redirectError(log.toFile())
              .start();
      responses = process.inputReader(StandardCharsets.UTF_8);
      commands = process.outputWriter(StandardCharsets.UTF_8);
    }

    void send(String request) throws IOException {
      commands.write(request);
      commands.newLine();
      commands.flush();
    }

    String reply() throws IOException, InterruptedException {
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (System.nanoTime() < deadline) {
        if (responses.ready()) {
          String line = responses.readLine();
          if (line != null && line.startsWith(RESPONSE)) {
            return line.substring(RESPONSE.length());
          }
        } else if (!process.isAlive()) {
          break;
        } else {
          Thread.sleep(10);
        }
      }
      throw new IOException("Missing response from benchmark JVM. See " + log);
    }

    void await(String value) throws IOException, InterruptedException {
      assertEquals(value, reply());
    }

    long hits() throws IOException, InterruptedException {
      send("hits");
      return Long.parseLong(reply());
    }

    void kill() throws InterruptedException {
      process.destroyForcibly();
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Unable to stop benchmark JVM");
    }

    @Override
    public void close() {
      try {
        commands.close();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          kill();
        }
      } catch (InterruptedException exception) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      } catch (IOException exception) {
        process.destroyForcibly();
      } finally {
        try {
          responses.close();
        } catch (IOException ignored) {
          // The worker has already exited or been terminated.
        }
      }
    }
  }

  public static final class Worker {
    public static void main(String[] arguments) throws Exception {
      var settings = new Properties();
      try (var reader = Files.newBufferedReader(Path.of(arguments[0]))) {
        settings.load(reader);
      }
      try (var factory = open(settings);
          var commands =
              new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
          var output =
              new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
        respond(output, "ready");
        for (String line; (line = commands.readLine()) != null; ) {
          String[] request = line.split(" ");
          String response =
              switch (request[0]) {
                case "hits" -> Long.toString(hitCount(factory));
                case "replayhits" ->
                    Integer.toString(
                        new GitHistorySearchService(factory)
                            .searchCommitTextSummaries(REPLAY_REPOSITORY, REPLAY_TEXT, 100)
                            .size());
                case "write", "rollback", "replay" -> {
                  persist(
                      factory,
                      request[0],
                      Integer.parseInt(request[1]),
                      Integer.parseInt(request[2]));
                  yield "ok";
                }
                default ->
                    throw new IllegalArgumentException("Unknown benchmark command: " + request[0]);
              };
          respond(output, response);
        }
      }
    }

    private static void respond(BufferedWriter output, String value) throws IOException {
      output.write(RESPONSE + value);
      output.newLine();
      output.flush();
    }

    private static void persist(
        SessionFactory factory, String operation, int first, int documents) {
      try (var session = factory.openSession()) {
        var transaction = session.beginTransaction();
        for (int id = first; id < first + documents; id++) {
          var row = new GitCommitIndex();
          String key = Integer.toHexString(id);
          row.setProjectionKey(key);
          row.setObjectId("0".repeat(40 - key.length()) + key);
          row.setRepositoryName(
              "replay".equals(operation) ? REPLAY_REPOSITORY : "topology/commits");
          row.setShortMessage(
              "replay".equals(operation) ? REPLAY_TEXT : "remote indexing benchmark");
          row.setFullMessage(row.getShortMessage());
          row.setCommitterTime(Instant.ofEpochSecond(id));
          row.setChangedPaths("src/Topology.java");
          row.setChangedText("class Topology { int revision = " + id + "; }");
          session.persist(row);
        }
        if ("rollback".equals(operation)) {
          session.flush();
          transaction.rollback();
        } else {
          transaction.commit();
        }
      }
    }
  }
}
