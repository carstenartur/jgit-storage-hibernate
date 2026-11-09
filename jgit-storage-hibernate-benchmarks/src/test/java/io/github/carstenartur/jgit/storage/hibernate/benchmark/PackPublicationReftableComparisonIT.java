/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static io.github.carstenartur.jgit.storage.hibernate.benchmark.PackPublicationPairedBenchmarkIT.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** Four fixed baseline/candidate pairs; always size policy and one shared host fixture. */
class PackPublicationReftableComparisonIT {
  static final List<String> VARIANTS = List.of("baseline", "candidate", "candidate", "baseline",
      "baseline", "candidate", "candidate", "baseline");
  private static final Cell CELL = Cell.parse("independent-16w-0ms");

  @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
  void retainsEightFixedImplementationAttempts() throws Exception {
    String source = sourceProperty("jgit.storage.benchmark.source");
    String tree = sourceProperty("jgit.storage.benchmark.sourceTree");
    Path root = Path.of(System.getProperty("benchmark.resultDirectory",
        "target/publication-reftable-comparison")).toAbsolutePath();
    Files.createDirectories(root.resolve("raw"));
    if (Files.exists(root.resolve("protocol.json"))) throw new IllegalStateException("Refusing to overwrite a comparison");
    JsonObject inputs = JsonParser.parseString(Files.readString(root.resolve("variants.json"))).getAsJsonObject();
    if (!source.equals(inputs.get("harnessSourceCommit").getAsString())
        || !tree.equals(inputs.get("harnessSourceTree").getAsString())) {
      throw new IllegalStateException("Prepared variants do not belong to this harness source");
    }
    JsonObject variants = inputs.getAsJsonObject("variants");
    String expandedClasspath = PublicationVariantClasspath.current();
    Map<String, String> classpaths = new LinkedHashMap<>();
    for (String variant : List.of("baseline", "candidate")) {
      JsonObject input = variants.getAsJsonObject(variant);
      Path jar = root.resolve("inputs/" + variant + "-core.jar").toRealPath();
      if (!input.get("jar").getAsString().equals("inputs/" + variant + "-core.jar")
          || !PublicationVariantClasspath.sha256(jar).equals(input.get("jarSha256").getAsString())) {
        throw new IllegalStateException("Prepared core JAR differs");
      }
      classpaths.put(variant, PublicationVariantClasspath.replace(expandedClasspath, jar));
    }
    if (Boolean.getBoolean("jmh.separateClasspathJAR")) {
      throw new IllegalStateException("Comparison requires an explicit child classpath");
    }
    Map<String, Object> protocol = protocol(CELL, source, tree);
    protocol.put("schema", "jgit-reftable-controlled/v1");
    protocol.put("diagnosticOnly", true);
    protocol.put("forkVariants", VARIANTS);
    protocol.put("pairCount", 4);
    protocol.put("policies", List.of("size"));
    protocol.put("jdkRuntime", "21.0.13+9");
    protocol.remove("pairStages");
    protocol.remove("marginPercent");
    protocol.remove("order");
    writeJson(root.resolve("protocol.json"), protocol);
    writeJson(root.resolve("trials.json"), List.of());
    writeJson(root.resolve("outcomes.json"), List.of());
    PackPublicationGcDiagnosticIT.requireOriginalRuntime();

    Path databaseDirectory = Files.createTempDirectory("jgit-reftable-comparison-postgresql-");
    String fileStoreType = Files.getFileStore(databaseDirectory).type();
    if (fileStoreType.equalsIgnoreCase("tmpfs") || fileStoreType.equalsIgnoreCase("ramfs")) {
      throw new IllegalStateException("Comparison requires a disk-backed database");
    }
    Path connectionFile = null;
    List<Map<String, Object>> trials = new ArrayList<>();
    try (Network network = Network.newNetwork();
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.10-alpine")
            .withDatabaseName("publication_paired").withUsername("benchmark").withPassword("benchmark")
            .withNetwork(network).withNetworkAliases("publication-paired-postgresql")
            .withFileSystemBind(databaseDirectory.toString(), "/var/lib/postgresql/data", BindMode.READ_WRITE)
            .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on",
                "-c", "full_page_writes=on", "-c", "shared_buffers=128MB",
                "-c", "max_connections=100", "-c", "max_wal_size=1GB");
        ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(network)) {
      postgres.start();
      toxiproxy.start();
      new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort()).createProxy(
          "publication-paired", "0.0.0.0:8666", "publication-paired-postgresql:5432");
      String url = "jdbc:postgresql://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666)
          + "/" + postgres.getDatabaseName();
      connectionFile = writeConnectionFile(url, postgres);
      Path privateConnection = connectionFile;
      try (var observer = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        Map<String, Object> environment = environment(observer, CELL, source, tree, fileStoreType, postgres);
        writeJson(root.resolve("environment.json"), environment);
        PublicationDiagnosticSequence.run(VARIANTS, (ordinal, variant) -> {
          int pair = (ordinal + 1) / 2;
          String base = "raw/pair-%02d-%s".formatted(pair, variant);
          JsonObject input = variants.getAsJsonObject(variant);
          String classpath = classpaths.get(variant);
          JsonObject identity = expectedIdentity(root, base, ordinal, variant, source, input, classpath);
          var original = options(CELL, "size", privateConnection, root.resolve(base + ".json"), root.resolve(base + ".log"));
          List<String> args = new ArrayList<>(original.getJvmArgs().get());
          args.addAll(PackPublicationGcDiagnosticIT.loggingOptions(root.resolve(base)));
          JsonArray expectedArguments = new JsonArray();
          args.forEach(expectedArguments::add);
          identity.add("jvmArgs", expectedArguments);
          String encoded = Base64.getEncoder().encodeToString(identity.toString().getBytes(StandardCharsets.UTF_8));
          var selected = new OptionsBuilder().parent(original).jvmArgs(args.toArray(String[]::new))
              .addProfiler(PublicationCoreIdentityProfiler.class, encoded).build();
          Map<String, Object> trial = new LinkedHashMap<>();
          trial.put("ordinal", ordinal);
          trial.put("pair", pair);
          trial.put("position", (ordinal - 1) % 2);
          trial.put("variant", variant);
          trial.put("policy", "size");
          trial.put("sourceCommit", source);
          trial.put("librarySourceCommit", input.get("sourceCommit").getAsString());
          trial.put("jarSha256", input.get("jarSha256").getAsString());
          trial.put("classSha256", input.get("classSha256").getAsString());
          trial.put("classpath", classpath);
          trial.put("identity", base + "-identity.json");
          trial.put("result", base + ".json");
          trial.put("output", base + ".log");
          trial.put("jvmArgs", args);
          trial.put("startedAt", Instant.now().toString());
          trials.add(trial);
          writeJson(root.resolve("trials.json"), trials);
          try {
            trial.put("checkpointBefore", checkpointState(observer));
            writeJson(root.resolve("trials.json"), trials);
            var results = PublicationVariantClasspath.with(classpath, () -> new Runner(selected).run());
            if (results.size() != 1 || !Files.isRegularFile(root.resolve(base + "-identity.json"))) {
              throw new IllegalStateException("Completed fork lacks its result or actual child identity");
            }
          } catch (RunnerException childFailure) {
            if (Files.exists(root.resolve(base + "-identity-failure.json"))) {
              throw new IllegalStateException("Actual child identity failed; comparison stopped", childFailure);
            }
            throw new PublicationDiagnosticSequence.ForkFailure(childFailure);
          } finally {
            trial.put("completedAt", Instant.now().toString());
            try { trial.put("checkpointAfter", checkpointState(observer)); }
            catch (Exception failure) { trial.put("checkpointAfterFailureType", failure.getClass().getName()); }
            writeJson(root.resolve("trials.json"), trials);
          }
        }, retained -> writeJson(root.resolve("outcomes.json"), retained.stream().map(
            outcome -> new Outcome(outcome.ordinal(), outcome.policy(), outcome.status(), outcome.failureType())).toList()));
        environment.put("finalRttSamplesMillis", calibrate(observer, 0));
        environment.put("completedAt", Instant.now().toString());
        writeJson(root.resolve("environment.json"), environment);
      }
    } finally {
      if (connectionFile != null) Files.deleteIfExists(connectionFile);
      writeJson(root.resolve("trials.json"), trials);
    }
  }

  private static JsonObject expectedIdentity(Path root, String base, int ordinal, String variant,
      String source, JsonObject input, String classpath) throws Exception {
    JsonObject expected = new JsonObject();
    expected.addProperty("ordinal", ordinal);
    expected.addProperty("variant", variant);
    expected.addProperty("harnessSourceCommit", source);
    expected.addProperty("librarySourceCommit", input.get("sourceCommit").getAsString());
    expected.addProperty("librarySourceTree", input.get("sourceTree").getAsString());
    expected.addProperty("jarPath", root.resolve(input.get("jar").getAsString()).toRealPath().toString());
    expected.addProperty("jarSha256", input.get("jarSha256").getAsString());
    expected.addProperty("classResource", PublicationVariantClasspath.CORE_RESOURCE);
    expected.addProperty("classSha256", input.get("classSha256").getAsString());
    expected.addProperty("classpath", classpath);
    expected.addProperty("javaVersion", System.getProperty("java.version"));
    expected.addProperty("javaVmVersion", System.getProperty("java.vm.version"));
    expected.addProperty("output", root.resolve(base + "-identity.json").toString());
    expected.addProperty("failureOutput", root.resolve(base + "-identity-failure.json").toString());
    return expected;
  }

  record Outcome(int ordinal, String variant, String status, String failureType) {}
}
