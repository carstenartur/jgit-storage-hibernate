/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

/** Hibernate settings used by the storage backend's default bootstrap helper. */
public final class HibernateStorageSettings {

  /** Hibernate's JDBC statement batch-size setting. */
  public static final String JDBC_BATCH_SIZE = "hibernate.jdbc.batch_size";

  /** Hibernate's insert-ordering setting. */
  public static final String ORDER_INSERTS = "hibernate.order_inserts";

  /**
   * Minimum extension size eligible for invisible persistence before the short repository
   * publication lock is acquired.
   *
   * <p>The value is interpreted as bytes. Inline extensions always retain the existing
   * single-transaction path even when a lower value is configured. Set this property to {@link
   * Long#MAX_VALUE} to disable adaptive pre-persistence.
   */
  public static final String PACK_PREPERSIST_THRESHOLD_BYTES =
      "jgit.storage.hibernate.pack.prepersist.threshold.bytes";

  /**
   * Conservative default aligned with the pack writer's bounded eight-chunk persistence window.
   * With one MiB chunks, one pending ORM batch retains at most roughly eight MiB of payload data.
   */
  public static final int DEFAULT_JDBC_BATCH_SIZE = 8;

  /**
   * Default that keeps every inline extension in the one-transaction path and pre-persists every
   * chunked extension.
   */
  public static final long DEFAULT_PACK_PREPERSIST_THRESHOLD_BYTES = 256L * 1024L + 1L;

  private HibernateStorageSettings() {}
}
