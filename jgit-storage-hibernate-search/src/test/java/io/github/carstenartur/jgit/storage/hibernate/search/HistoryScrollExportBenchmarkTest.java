/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryScrollExportBenchmarkTest {

  @TempDir Path directory;

  @Test
  void recordsNonemptyContinuationPagesForBothPaths() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.url", "jdbc:h2:file:" + directory.resolve("db"));
    properties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    properties.setProperty("hibernate.hbm2ddl.auto", "create");

    Path output = directory.resolve("evidence");
    HistoryScrollExportBenchmark.run(
        properties, directory.resolve("index"), output, 11, List.of(4), 1);

    List<String> exports = Files.readAllLines(output.resolve("exports.csv"));
    assertEquals(3, exports.size(), "header and one full export per query path");
    assertTrue(exports.get(1).startsWith("orm,11,4,1,11,3,"));
    assertTrue(exports.get(2).startsWith("search,11,4,1,11,3,"));

    List<String> pages = Files.readAllLines(output.resolve("offsets.csv"));
    assertEquals(7, pages.size(), "three nonempty pages per query path");
    for (String engine : List.of("orm", "search")) {
      String[] deepPage =
          pages.stream()
              .filter(line -> line.startsWith(engine + ",11,4,1,8,3,"))
              .findFirst()
              .orElseThrow()
              .split(",");
      assertEquals("0".repeat(39) + "1", deepPage[9]);
      assertEquals("0".repeat(39) + "3", deepPage[10]);
    }

    Properties metadata = new Properties();
    try (var reader = Files.newBufferedReader(output.resolve("environment.properties"))) {
      metadata.load(reader);
    }
    assertEquals("H2", metadata.getProperty("database.product"));
    assertEquals("10000", metadata.getProperty("guard.default"));
    assertEquals("11", metadata.getProperty("guard.benchmark"));
  }
}
