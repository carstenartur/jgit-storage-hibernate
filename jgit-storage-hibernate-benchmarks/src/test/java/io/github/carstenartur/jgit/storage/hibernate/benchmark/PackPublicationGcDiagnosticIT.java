/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.calibrate;
import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.checkpointState;
import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.environment;
import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.sourceProperty;
import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.writeConnectionFile;
import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.writeJson;

import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** Four fresh JVMs for allocation diagnosis; never a resumed performance qualification. */
class PackPublicationGcDiagnosticIT {
  private static final PackPublicationPairedBenchmarkIT.Cell CELL =
      PackPublicationPairedBenchmarkIT.Cell.parse("independent-16w-0ms");

  @Test
  @Timeout(value = 20, unit = TimeUnit.MINUTES)
  void retainsFourFixedDiagnosticAttempts() throws Exception {
    String source = sourceProperty("jgit.storage.benchmark.source");
    String tree = sourceProperty("jgit.storage.benchmark.sourceTree");
    Path root = Path.of(System.getProperty("benchmark.resultDirectory",
        "target/publication-gc-diagnostic")).toAbsolutePath();
    Files.createDirectories(root);
    if (Files.exists(root.resolve("protocol.json"))) {
      throw new IllegalStateException("Refusing to overwrite a diagnostic");
    }
    Files.createDirectories(root.resolve("raw"));
    Map<String, Object> protocol = PackPublicationPairedBenchmarkIT.protocol(CELL, source, tree);
    String variant = System.getProperty("jgit.storage.benchmark.publication.diagnostic.variant", "original");
    if (!List.of("original", "reftable").contains(variant)) {
      throw new IllegalArgumentException("Unknown diagnostic variant");
    }
    protocol.put("schema", "jgit-publication-gc-diagnostic/v1");
    protocol.put("diagnosticOnly", true);
    protocol.put("variant", variant);
    protocol.put("forkPolicies", PublicationDiagnosticSequence.POLICIES);
    protocol.put("jdkRuntime", "21.0.13+9");
    protocol.put("originalStudyRun", 42431132779L);
    protocol.remove("pairStages");
    protocol.remove("marginPercent");
    protocol.remove("order");
    writeJson(root.resolve("protocol.json"), protocol);
    writeJson(root.resolve("trials.json"), List.of());
    writeJson(root.resolve("outcomes.json"), List.of());
    requireOriginalRuntime();
    probeLoggingOptions(root);

    Path databaseDirectory = Files.createTempDirectory("jgit-diagnostic-postgresql-");
    String fileStoreType = Files.getFileStore(databaseDirectory).type();
    if (fileStoreType.equalsIgnoreCase("tmpfs") || fileStoreType.equalsIgnoreCase("ramfs")) {
      throw new IllegalStateException("This diagnostic requires the original disk-backed fixture");
    }
    Path connectionFile = null;
    List<Map<String, Object>> trials = new ArrayList<>();
    try (Network network = Network.newNetwork();
        PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.10-alpine")
                .withDatabaseName("publication_paired")
                .withUsername("benchmark")
                .withPassword("benchmark")
                .withNetwork(network)
                .withNetworkAliases("publication-paired-postgresql")
                .withFileSystemBind(databaseDirectory.toString(), "/var/lib/postgresql/data",
                    BindMode.READ_WRITE)
                .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on",
                    "-c", "full_page_writes=on", "-c", "shared_buffers=128MB",
                    "-c", "max_connections=100", "-c", "max_wal_size=1GB");
        ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(network)) {
      postgres.start();
      toxiproxy.start();
      ToxiproxyClient client =
          new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
      client.createProxy("publication-paired", "0.0.0.0:8666",
          "publication-paired-postgresql:5432");
      String url = "jdbc:postgresql://" + toxiproxy.getHost() + ":"
          + toxiproxy.getMappedPort(8666) + "/" + postgres.getDatabaseName();
      connectionFile = writeConnectionFile(url, postgres);
      Path privateConnection = connectionFile;
      try (Connection observer =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        Map<String, Object> environment = environment(observer, CELL, source, tree, fileStoreType, postgres);
        writeJson(root.resolve("environment.json"), environment);
        List<PublicationDiagnosticSequence.Outcome> outcomes = PublicationDiagnosticSequence.run(
            (ordinal, policy) -> {
              String base = "raw/fork-%02d-%s".formatted(ordinal, policy);
              Options options = diagnosticOptions(policy, privateConnection, root, base);
              Map<String, Object> trial = new LinkedHashMap<>();
              trial.put("ordinal", ordinal);
              trial.put("policy", policy);
              trial.put("sourceCommit", source);
              trial.put("result", base + ".json");
              trial.put("output", base + ".log");
              trial.put("jvmArgs", options.getJvmArgs().get());
              trial.put("startedAt", Instant.now().toString());
              trials.add(trial);
              writeJson(root.resolve("trials.json"), trials);
              try {
                trial.put("checkpointBefore", checkpointState(observer));
                writeJson(root.resolve("trials.json"), trials);
                Collection<RunResult> results = new Runner(options).run();
                if (results.size() != 1) throw new IllegalStateException("Expected one fresh fork result");
              } catch (RunnerException childFailure) {
                throw new PublicationDiagnosticSequence.ForkFailure(childFailure);
              } finally {
                trial.put("completedAt", Instant.now().toString());
                // A failed observer must not replace the original child outcome.
                try {
                  trial.put("checkpointAfter", checkpointState(observer));
                } catch (Exception observerFailure) {
                  trial.put("checkpointAfterFailureType", observerFailure.getClass().getName());
                }
                writeJson(root.resolve("trials.json"), trials);
              }
            },
            retained -> writeJson(root.resolve("outcomes.json"), retained));
        environment.put("finalRttSamplesMillis", calibrate(observer, 0));
        environment.put("completedAt", Instant.now().toString());
        writeJson(root.resolve("environment.json"), environment);
        System.out.printf("Retained %d diagnostic attempts; failed children: %d%n", outcomes.size(),
            outcomes.stream().filter(outcome -> !outcome.status().equals("completed")).count());
      }
    } finally {
      if (connectionFile != null) Files.deleteIfExists(connectionFile);
      writeJson(root.resolve("trials.json"), trials);
    }
  }

  private static Options diagnosticOptions(String policy, Path connectionFile, Path root, String base) {
    Options original = PackPublicationPairedBenchmarkIT.options(CELL, policy, connectionFile,
        root.resolve(base + ".json"), root.resolve(base + ".log"));
    List<String> args = new ArrayList<>(original.getJvmArgs().get());
    args.addAll(loggingOptions(root.resolve(base)));
    return new OptionsBuilder().parent(original).jvmArgs(args.toArray(String[]::new)).build();
  }

  private static List<String> loggingOptions(Path base) {
    return List.of(
        "-Xlog:gc*=info,gc+heap=debug,gc+humongous=debug,gc+alloc=trace:file=" + base
            + "-gc.log:uptimemillis,level,tags:filecount=2,filesize=16m",
        "-Xlog:gc+jni=debug:file=" + base
            + "-jni.log:uptimemillis,level,tags:filecount=2,filesize=16m",
        "-Xlog:gc+init=info:file=" + base + "-init.log:uptimemillis,level,tags");
  }

  private static void requireOriginalRuntime() {
    String version = System.getProperty("java.vm.version", "");
    if (!System.getProperty("java.version", "").equals("21.0.13")
        || !version.matches("21\\.0\\.13\\+9(?:-LTS)?")
        || !System.getProperty("java.vm.name", "").equals("OpenJDK 64-Bit Server VM")) {
      throw new IllegalStateException("Diagnostic requires the original HotSpot 21.0.13+9 runtime");
    }
  }

  private static void probeLoggingOptions(Path root) throws Exception {
    Files.createDirectories(root.resolve("preflight"));
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xms256m");
    command.add("-Xmx1024m");
    command.addAll(loggingOptions(root.resolve("preflight/options")));
    command.add("-version");
    Process probe = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(root.resolve("preflight/jvm-options.log").toFile()).start();
    if (!probe.waitFor(30, TimeUnit.SECONDS)) {
      probe.destroyForcibly();
      throw new IllegalStateException("Diagnostic JVM option probe timed out");
    }
    if (probe.exitValue() != 0) {
      throw new IllegalStateException("Diagnostic JVM rejected the logging options before any workload");
    }
  }
}
