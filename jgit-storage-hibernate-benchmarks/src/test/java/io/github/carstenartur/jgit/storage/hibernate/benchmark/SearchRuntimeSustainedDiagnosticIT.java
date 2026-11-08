/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Explicit manual native diagnostic; it does not add work to the ordinary JMH matrix. */
@EnabledIfSystemProperty(named = "search.runtime.sustained.run", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
class SearchRuntimeSustainedDiagnosticIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("runtime_diagnostic")
          .withUsername("benchmark")
          .withPassword("benchmark")
          .withCommand(
              "postgres",
              "-c",
              "fsync=on",
              "-c",
              "synchronous_commit=on",
              "-c",
              "full_page_writes=on");

  @TempDir Path root;

  @Test
  void retainsSeparateTimingAndActivityPassesWithDrainAndReopen() throws Exception {
    String scenario = System.getProperty("search.runtime.sustained.scenario", "reference");
    String resources = System.getProperty("search.runtime.resources", "normal");
    if (!java.util.Set.of("normal", "constrained").contains(resources))
      throw new IllegalArgumentException("Use an explicit normal or constrained resource profile");
    int expectedProcessors = resources.equals("normal") ? 4 : 1;
    long expectedHeap = resources.equals("normal") ? 1024L * 1024 * 1024 : 512L * 1024 * 1024;
    if (Runtime.getRuntime().availableProcessors() != expectedProcessors
        || Runtime.getRuntime().maxMemory() > expectedHeap)
      throw new IllegalStateException("JVM resource budget differs from the selected profile");
    Path output =
        Path.of(
            System.getProperty(
                "search.runtime.sustained.output", "target/search-runtime-sustained"));
    Properties connection = new Properties();
    connection.setProperty("hibernate.connection.url", POSTGRESQL.getJdbcUrl());
    connection.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
    connection.setProperty("hibernate.connection.username", POSTGRESQL.getUsername());
    connection.setProperty("hibernate.connection.password", POSTGRESQL.getPassword());
    for (boolean traced : new boolean[] {false, true}) {
      String pass = traced ? "trace" : "timing";
      var options =
          new SearchRuntimeSustainedDiagnostic.Options(
              scenario, resources, traced, 1000, 10, 100, 50, 100, 1500, 30);
      SearchRuntimeSustainedDiagnostic.run(
          connection, root.resolve(pass), output.resolve(pass), options);
      if (!Files.isRegularFile(output.resolve(pass).resolve("completion.properties")))
        throw new IllegalStateException("Diagnostic did not finish " + pass);
    }
  }
}
