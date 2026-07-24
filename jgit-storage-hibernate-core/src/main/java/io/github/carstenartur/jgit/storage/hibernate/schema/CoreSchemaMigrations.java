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

  /** Flyway location for HSQLDB migrations. */
  public static final String HSQLDB_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/core/hsqldb";

  /** Flyway location for PostgreSQL migrations. */
  public static final String POSTGRESQL_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/core/postgresql";

  /** Flyway location for adopting the pre-library schema on HSQLDB. */
  public static final String HSQLDB_LEGACY_ADOPTION_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/core/adoption/hsqldb";

  /** Flyway location for adopting the pre-library schema on PostgreSQL. */
  public static final String POSTGRESQL_LEGACY_ADOPTION_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/core/adoption/postgresql";

  /** Dedicated Flyway schema history table for core storage. */
  public static final String SCHEMA_HISTORY_TABLE =
      "jgit_storage_hibernate_core_schema_history";

  /** Dedicated Flyway history table for one-time pre-library schema adoption. */
  public static final String LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE =
      "jgit_storage_hibernate_core_adoption_history";

  /** Baseline used when installing into a schema that contains only unrelated tables. */
  public static final String PRE_MIGRATION_BASELINE_VERSION = "0";

  /** Description for the pre-migration baseline of a new module installation. */
  public static final String PRE_MIGRATION_BASELINE_DESCRIPTION =
      "before jgit-storage-hibernate-core migrations";

  /** Schema version already produced by jgit-storage-hibernate-core 0.1.4. */
  public static final String LEGACY_SCHEMA_VERSION = "0.1.4";

  /** Description used when baselining a verified 0.1.4 schema. */
  public static final String LEGACY_BASELINE_DESCRIPTION =
      "jgit-storage-hibernate-core 0.1.4";

  /** Current physical core schema version after the 0.1.5 schema-history migration. */
  public static final String CURRENT_SCHEMA_VERSION = "0.1.5";

  /** Latest version of the pre-library/Sandbox/Taxonomy adoption migration stream. */
  public static final String LEGACY_ADOPTION_VERSION = "2";

  private CoreSchemaMigrations() {}
}
