/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.schema;

/**
 * Stable classpath contract for the versioned core database migrations.
 *
 * <p>The migrations are Flyway-compatible SQL resources packaged in the core artifact. Consumers
 * select the location matching their database and use a dedicated schema history table so core and
 * optional projection migrations can evolve independently in one database schema.
 */
public final class CoreSchemaMigrations {

  /** Flyway location for H2 migrations. */
  public static final String H2_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/core/h2";

  /** Flyway location for PostgreSQL migrations. */
  public static final String POSTGRESQL_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/core/postgresql";

  /** Dedicated Flyway schema history table for core storage. */
  public static final String SCHEMA_HISTORY_TABLE =
      "jgit_storage_hibernate_core_schema_history";

  /** Schema version already produced by jgit-storage-hibernate-core 0.1.4. */
  public static final String LEGACY_SCHEMA_VERSION = "0.1.4";

  /** Description used when baselining a verified 0.1.4 schema. */
  public static final String LEGACY_BASELINE_DESCRIPTION =
      "jgit-storage-hibernate-core 0.1.4";

  private CoreSchemaMigrations() {}
}
