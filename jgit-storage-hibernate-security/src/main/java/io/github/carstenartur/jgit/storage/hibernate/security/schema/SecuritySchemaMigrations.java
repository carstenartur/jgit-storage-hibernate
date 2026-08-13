/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security.schema;

/** Stable classpath contract for Security-owned versioned database migrations. */
public final class SecuritySchemaMigrations {

  /** Flyway location for H2 migrations. */
  public static final String H2_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/security/h2";

  /** Flyway location for HSQLDB migrations. */
  public static final String HSQLDB_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/security/hsqldb";

  /** Flyway location for PostgreSQL migrations. */
  public static final String POSTGRESQL_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/security/postgresql";

  /** Flyway location for Microsoft SQL Server migrations. */
  public static final String SQL_SERVER_LOCATION =
      "classpath:db/migration/jgit-storage-hibernate/security/sqlserver";

  /** Dedicated Flyway history table for the optional Security capability. */
  public static final String SCHEMA_HISTORY_TABLE =
      "jgit_storage_hibernate_security_schema_history";

  /** Baseline used when Security is installed beside Core in an existing schema. */
  public static final String PRE_MIGRATION_BASELINE_VERSION = "0";

  /** Latest schema version produced by this module. */
  public static final String CURRENT_SCHEMA_VERSION = "0.11.1";

  private SecuritySchemaMigrations() {}
}
