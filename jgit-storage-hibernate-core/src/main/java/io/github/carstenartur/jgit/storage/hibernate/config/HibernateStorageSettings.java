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

  /** Hibernate's update-ordering setting. */
  public static final String ORDER_UPDATES = "hibernate.order_updates";

  /**
   * Conservative default aligned with the pack writer's bounded eight-chunk persistence window.
   * With one MiB chunks, one pending ORM batch retains at most roughly eight MiB of payload data.
   */
  public static final int DEFAULT_JDBC_BATCH_SIZE = 8;

  private HibernateStorageSettings() {}
}
