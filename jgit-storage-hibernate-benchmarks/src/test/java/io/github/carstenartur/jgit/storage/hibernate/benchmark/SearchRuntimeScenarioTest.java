/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SearchRuntimeScenarioTest {

  @Test
  void parsesHyphenatedSynchronizationStrategies() {
    SearchRuntimeScenario writeSync = SearchRuntimeScenario.fromId("sync-write-sync-r500");
    assertEquals("write-sync", writeSync.synchronization());
    assertEquals(500, writeSync.refreshIntervalMs());

    SearchRuntimeScenario readSync = SearchRuntimeScenario.fromId("sync-read-sync-r1000");
    assertEquals("read-sync", readSync.synchronization());
    assertEquals(1_000, readSync.refreshIntervalMs());
  }

  @Test
  void referenceLeavesLuceneWriterAndThreadDefaultsUntouched() {
    SearchRuntimeScenario reference = SearchRuntimeScenario.fromId("reference");
    assertEquals("write-sync", reference.synchronization());
    assertEquals(0, reference.refreshIntervalMs());
    assertNull(reference.writerRamBufferMb());
    assertNull(reference.backendThreads());
    assertEquals(50, reference.projectionBatchSize());

    Properties properties = new Properties();
    reference.apply(properties);
    assertEquals("write-sync", properties.get(SearchRuntimeScenario.SYNCHRONIZATION_PROPERTY));
    assertEquals("0", properties.get(SearchRuntimeScenario.REFRESH_INTERVAL_PROPERTY));
    assertEquals("50", properties.get("hibernate.jdbc.batch_size"));
  }

  @Test
  void fullMatrixCoversEveryRequestedFamilyWithoutDuplicates() {
    List<String> ids = SearchRuntimeScenario.fullScenarioIds();
    assertEquals(ids.size(), new HashSet<>(ids).size());

    for (String synchronization : List.of("async", "write-sync", "read-sync", "sync")) {
      for (int refresh : List.of(0, 100, 500, 1_000)) {
        assertTrue(ids.contains("sync-" + synchronization + "-r" + refresh));
      }
    }
    for (int ram : List.of(16, 32, 64, 128, 256)) {
      for (int threads : List.of(1, 2, 4, 8)) {
        assertTrue(ids.contains("writer-ram" + ram + "-t" + threads));
      }
    }
    for (int batch : List.of(1, 50, 250, 500)) {
      assertTrue(ids.contains("batch-" + batch));
    }
  }

  @Test
  void rejectsUnsupportedScenarioValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeScenario.fromId("sync-write-sync-r42"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeScenario.fromId("writer-ram12-t4"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeScenario.fromId("batch-17"));
  }

  @Test
  void malformedNumericScenarioValuesIdentifyTheScenario() {
    for (String scenario :
        List.of(
            "sync-write-sync-rfast",
            "writer-ramlots-t4",
            "writer-ram64-tmany",
            "batch-many")) {
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class, () -> SearchRuntimeScenario.fromId(scenario));
      assertTrue(exception.getMessage().contains(scenario));
      assertTrue(exception.getMessage().contains("must be an integer"));
    }
  }
}
