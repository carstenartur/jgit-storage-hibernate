/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchRuntimeSustainedDiagnosticTest {
  @TempDir Path root;

  @ParameterizedTest
  @CsvSource({"reference,true", "sync-async-r500,true", "sync-async-r500,false"})
  void smallPersistentFixtureDrainsReopensAndEmitsRealActivity(String scenario, boolean traced)
      throws Exception {
    Path output =
        Files.createTempDirectory(Path.of("target"), "search-runtime-sustained-" + scenario + "-");
    Properties connection = new Properties();
    connection.setProperty(
        "hibernate.connection.url",
        "jdbc:hsqldb:file:" + root.resolve("database") + ";hsqldb.write_delay=false;shutdown=true");
    connection.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    connection.setProperty("hibernate.connection.username", "SA");
    var logger = Logger.getLogger("org.hibernate.search.lucene.infostream");
    var level = logger.getLevel();
    var handlers = logger.getHandlers();
    boolean parents = logger.getUseParentHandlers();
    var options =
        new SearchRuntimeSustainedDiagnostic.Options(
            scenario, "local-correctness", traced, 20, 2, 12, 12, 25, 150, 10);
    SearchRuntimeSustainedDiagnostic.run(connection, root.resolve("index"), output, options);
    System.out.println("Diagnostic correctness evidence: " + output.toAbsolutePath());

    assertTrue(
        Files.isRegularFile(output.resolve("completion.properties")),
        "A successful diagnostic must retain its completion and membership evidence");
    Properties completion = new Properties();
    try (var reader = Files.newBufferedReader(output.resolve("completion.properties"))) {
      completion.load(reader);
    }
    assertEquals("complete", completion.getProperty("status"));
    assertEquals("68", completion.getProperty("expected.commits"));
    assertEquals("68", completion.getProperty("reopened.visible.commits"));
    assertEquals("0", completion.getProperty("workers.after.close"));
    assertEquals("true", completion.getProperty("gate.entered"));
    assertEquals("true", completion.getProperty("gate.producer.incomplete"));
    assertEquals("true", completion.getProperty("membership.git.verified"));
    String trace = Files.readString(output.resolve("trace.csv"));
    if (traced) {
      assertTrue(trace.contains("getReader took"), "Exercise real NRT reader activity");
      assertTrue(trace.contains("after commitMerge:"), "Exercise real merge installations");
      assertTrue(trace.contains("done writing segments file"), "Exercise real commit publication");
    } else {
      assertFalse(
          trace.contains("\"IW\""), "Untraced observations must not include InfoStream work");
    }
    assertTrue(Files.size(output.resolve("sha256.properties")) > 100);
    assertEquals(level, logger.getLevel());
    assertArrayEquals(handlers, logger.getHandlers());
    assertEquals(parents, logger.getUseParentHandlers());
  }
}
