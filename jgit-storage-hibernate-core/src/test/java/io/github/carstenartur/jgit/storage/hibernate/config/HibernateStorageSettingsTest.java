/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HibernateStorageSettingsTest {

  @Test
  void explicitPackChunkBatchSizeTakesPrecedence() {
    assertEquals(
        50,
        HibernateStorageSettings.resolvePackChunkBatchSize(
            Map.of(
                HibernateStorageSettings.JDBC_BATCH_SIZE,
                "8",
                HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE,
                "50")));
  }

  @Test
  void positiveJdbcBatchSizeIsTheImplicitWriterWindow() {
    assertEquals(
        32,
        HibernateStorageSettings.resolvePackChunkBatchSize(
            Map.of(HibernateStorageSettings.JDBC_BATCH_SIZE, "32")));
  }

  @Test
  void disabledJdbcBatchingKeepsTheBoundedDefaultWindow() {
    assertEquals(
        HibernateStorageSettings.DEFAULT_PACK_CHUNK_BATCH_SIZE,
        HibernateStorageSettings.resolvePackChunkBatchSize(
            Map.of(HibernateStorageSettings.JDBC_BATCH_SIZE, "0")));
  }

  @Test
  void rejectsUnboundedOrMalformedWriterWindows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HibernateStorageSettings.resolvePackChunkBatchSize(
                Map.of(HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, "65")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HibernateStorageSettings.resolvePackChunkBatchSize(
                Map.of(HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, "many")));
  }
}
