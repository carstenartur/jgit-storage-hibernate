/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import java.util.Map;

/** Hibernate settings used by the storage backend's default bootstrap helper. */
public final class HibernateStorageSettings {

  /** Hibernate's JDBC statement batch-size setting. */
  public static final String JDBC_BATCH_SIZE = "hibernate.jdbc.batch_size";

  /** Hibernate's insert-ordering setting. */
  public static final String ORDER_INSERTS = "hibernate.order_inserts";

  /** Number of one-MiB pack chunks retained and submitted by one bounded writer batch. */
  public static final String PACK_CHUNK_BATCH_SIZE =
      "jgit.storage.hibernate.pack.chunk_batch_size";

  /**
   * Conservative default aligned with the pack writer's bounded eight-chunk persistence window.
   * With one MiB chunks, one pending ORM batch retains at most roughly eight MiB of payload data.
   */
  public static final int DEFAULT_JDBC_BATCH_SIZE = 8;

  /** Default bounded pack-chunk writer window. */
  public static final int DEFAULT_PACK_CHUNK_BATCH_SIZE = DEFAULT_JDBC_BATCH_SIZE;

  /** Hard safety ceiling for one writer's retained one-MiB chunk arrays. */
  public static final int MAX_PACK_CHUNK_BATCH_SIZE = 64;

  private HibernateStorageSettings() {}

  /**
   * Resolve the bounded pack-chunk writer window from Hibernate properties.
   *
   * <p>An explicit library setting takes precedence. Otherwise a positive Hibernate JDBC batch size
   * is reused so custom consumers do not accidentally request a larger JDBC batch than the writer
   * can fill. Values above the hard safety ceiling require an explicit redesign rather than
   * silently retaining unbounded payload arrays.
   *
   * @param properties Hibernate SessionFactory properties
   * @return validated chunk batch size
   */
  public static int resolvePackChunkBatchSize(Map<?, ?> properties) {
    Object explicit = properties.get(PACK_CHUNK_BATCH_SIZE);
    if (explicit != null && !explicit.toString().isBlank()) {
      return parsePackChunkBatchSize(explicit.toString(), PACK_CHUNK_BATCH_SIZE);
    }

    Object jdbcBatch = properties.get(JDBC_BATCH_SIZE);
    if (jdbcBatch != null && !jdbcBatch.toString().isBlank()) {
      int configuredJdbcBatch = parseInteger(jdbcBatch.toString(), JDBC_BATCH_SIZE);
      if (configuredJdbcBatch > 0) {
        return validatePackChunkBatchSize(configuredJdbcBatch, JDBC_BATCH_SIZE);
      }
    }
    return DEFAULT_PACK_CHUNK_BATCH_SIZE;
  }

  private static int parsePackChunkBatchSize(String configured, String propertyName) {
    return validatePackChunkBatchSize(parseInteger(configured, propertyName), propertyName);
  }

  private static int parseInteger(String configured, String propertyName) {
    try {
      return Integer.parseInt(configured.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          propertyName + " must be an integer but was '" + configured + "'", exception);
    }
  }

  private static int validatePackChunkBatchSize(int configured, String propertyName) {
    if (configured <= 0 || configured > MAX_PACK_CHUNK_BATCH_SIZE) {
      throw new IllegalArgumentException(
          propertyName
              + " must be between 1 and "
              + MAX_PACK_CHUNK_BATCH_SIZE
              + " but was "
              + configured);
    }
    return configured;
  }
}
