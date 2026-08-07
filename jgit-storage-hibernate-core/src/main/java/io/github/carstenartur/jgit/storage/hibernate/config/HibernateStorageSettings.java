/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import java.util.Locale;
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

  /** Pack-chunk writer selection: {@code auto}, {@code stateful} or {@code stateless}. */
  public static final String PACK_CHUNK_WRITER =
      "jgit.storage.hibernate.pack.chunk_writer";

  /** Minimum staged extension bytes at which {@code auto} selects the stateless writer. */
  public static final String STATELESS_MIN_PAYLOAD_BYTES =
      "jgit.storage.hibernate.pack.stateless_min_payload_bytes";

  /** Automatic size-based writer selection. */
  public static final String AUTO_CHUNK_WRITER = "auto";

  /** Always use the ordinary stateful Hibernate session for chunk rows. */
  public static final String STATEFUL_CHUNK_WRITER = "stateful";

  /** Always use the shared-transaction child StatelessSession for chunk rows. */
  public static final String STATELESS_CHUNK_WRITER = "stateless";

  /**
   * Evidence-based default selected from calibrated PostgreSQL/Toxiproxy measurements.
   *
   * <p>For a 48-MiB publication, increasing the bounded window from 8 to 16 removed three
   * sequential JDBC batch executions while adding at most eight MiB of retained chunk payload per
   * active writer. Larger values continued to improve high-RTT deployments, but with sharply
   * diminishing saved round trips per additional retained MiB. Sixteen is therefore the portable
   * default; latency-sensitive deployments may configure 32 or 50 explicitly.
   */
  public static final int DEFAULT_JDBC_BATCH_SIZE = 16;

  /** Default bounded pack-chunk writer window. */
  public static final int DEFAULT_PACK_CHUNK_BATCH_SIZE = DEFAULT_JDBC_BATCH_SIZE;

  /** Hard safety ceiling for one writer's retained one-MiB chunk arrays. */
  public static final int MAX_PACK_CHUNK_BATCH_SIZE = 64;

  /**
   * Default automatic stateless threshold selected by the 16/128/512-MiB matrix.
   *
   * <p>At all three sizes stateless ORM reduced allocation by about 16–18%, reduced flush and GC
   * work, preserved identical JDBC execution counts and byte integrity, and did not regress the
   * elapsed-time point estimate. Sixteen MiB is therefore the lowest measured material crossover.
   */
  public static final long DEFAULT_STATELESS_MIN_PAYLOAD_BYTES = 16L * 1024L * 1024L;

  private HibernateStorageSettings() {}

  /** Resolve and validate the pack chunk writer mode. */
  public static String resolvePackChunkWriter(Map<?, ?> properties) {
    Object configured = properties.get(PACK_CHUNK_WRITER);
    if (configured == null || configured.toString().isBlank()) {
      return AUTO_CHUNK_WRITER;
    }
    String mode = configured.toString().trim().toLowerCase(Locale.ROOT);
    return switch (mode) {
      case AUTO_CHUNK_WRITER, STATEFUL_CHUNK_WRITER, STATELESS_CHUNK_WRITER -> mode;
      default ->
          throw new IllegalArgumentException(
              PACK_CHUNK_WRITER
                  + " must be '"
                  + AUTO_CHUNK_WRITER
                  + "', '"
                  + STATEFUL_CHUNK_WRITER
                  + "' or '"
                  + STATELESS_CHUNK_WRITER
                  + "' but was '"
                  + configured
                  + "'");
    };
  }

  /** Resolve the non-negative auto-selection threshold in payload bytes. */
  public static long resolveStatelessMinPayloadBytes(Map<?, ?> properties) {
    Object configured = properties.get(STATELESS_MIN_PAYLOAD_BYTES);
    if (configured == null || configured.toString().isBlank()) {
      return DEFAULT_STATELESS_MIN_PAYLOAD_BYTES;
    }
    long value = parseLong(configured.toString(), STATELESS_MIN_PAYLOAD_BYTES);
    if (value < 0) {
      throw new IllegalArgumentException(
          STATELESS_MIN_PAYLOAD_BYTES + " must not be negative but was " + value);
    }
    return value;
  }

  /**
   * Resolve the bounded pack-chunk writer window from Hibernate properties.
   *
   * <p>An explicit library setting takes precedence. Otherwise a positive Hibernate JDBC batch size
   * is reused so custom consumers do not request a larger JDBC batch than the writer can fill. An
   * explicitly configured pack window must remain within the hard safety ceiling. A larger generic
   * Hibernate batch size is accepted for compatibility but the payload-retaining writer window is
   * capped at that ceiling.
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
        return Math.min(configuredJdbcBatch, MAX_PACK_CHUNK_BATCH_SIZE);
      }
    }
    return DEFAULT_PACK_CHUNK_BATCH_SIZE;
  }

  private static int parsePackChunkBatchSize(String configured, String propertyName) {
    return validatePackChunkBatchSize(parseInteger(configured, propertyName), propertyName);
  }

  private static int parseInteger(String configured, String propertyName) {
    long value = parseLong(configured, propertyName);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(propertyName + " exceeds the integer range: " + value);
    }
    return (int) value;
  }

  private static long parseLong(String configured, String propertyName) {
    try {
      return Long.parseLong(configured.trim());
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
