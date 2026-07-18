/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.schema;

/**
 * Stable classpath contract for the versioned generic search projection migrations.
 *
 * <p>The migrations are Flyway-compatible SQL resources packaged in the search artifact. They use
 * their own schema history table and are applied in addition to the core migrations.
 */
public final class SearchSchemaMigrations {

  /** Flyway location for H2 migrations. */
  public static final String H2_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/search/h2";

  /** Flyway location for PostgreSQL migrations. */
  public static final String POSTGRESQL_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/search/postgresql";

  /** Dedicated Flyway schema history table for generic search projections. */
  public static final String SCHEMA_HISTORY_TABLE =
      "jgit_storage_hibernate_search_schema_history";

  /** Baseline used when installing into a schema that contains only unrelated tables. */
  public static final String PRE_MIGRATION_BASELINE_VERSION = "0";

  /** Description for the pre-migration baseline of a new module installation. */
  public static final String PRE_MIGRATION_BASELINE_DESCRIPTION =
      "before jgit-storage-hibernate-search migrations";

  /** Search projection schema version already produced by version 0.1.4. */
  public static final String LEGACY_SCHEMA_VERSION = "0.1.4";

  /** Description used when baselining a verified 0.1.4 search projection schema. */
  public static final String LEGACY_BASELINE_DESCRIPTION =
      "jgit-storage-hibernate-search 0.1.4";

  private SearchSchemaMigrations() {}
}
