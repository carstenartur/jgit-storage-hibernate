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

  /** Maximum bytes retained by each repository instance's inline payload cache. */
  public static final String INLINE_PAYLOAD_CACHE_MAX_BYTES =
      "jgit.storage.hibernate.inline_payload_cache.max_bytes";

  /**
   * Conservative default aligned with the pack writer's bounded eight-chunk persistence window.
   * With one MiB chunks, one pending ORM batch retains at most roughly eight MiB of payload data.
   */
  public static final int DEFAULT_JDBC_BATCH_SIZE = 8;

  /**
   * Default memory bound for recently used inline PACK, INDEX, Reftable and auxiliary payloads.
   *
   * <p>The cache is repository-instance-local, stores only already committed rows and is keyed by
   * immutable database row identity. Setting {@link #INLINE_PAYLOAD_CACHE_MAX_BYTES} to {@code 0}
   * disables it.
   */
  public static final long DEFAULT_INLINE_PAYLOAD_CACHE_MAX_BYTES = 8L * 1024 * 1024;

  private HibernateStorageSettings() {}
}
