/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import org.hibernate.SessionFactory;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.HSQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/** Exact repository identity for the supported databases, before SQL pagination is applied. */
final class StructuredRepositoryPredicate {

  private StructuredRepositoryPredicate() {}

  static String matchingRepository(SessionFactory sessionFactory) {
    var dialect =
        sessionFactory.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect();
    String exact;
    if (dialect instanceof H2Dialect || dialect instanceof OracleDialect) {
      // Both functions preserve the string's code units/bytes, including trailing spaces.
      // The column and parameter use the same nationalized String mapping.
      exact =
          "sql('rawtohex(?)', c.repositoryName) = sql('rawtohex(?)', :repo)";
    } else if (dialect instanceof HSQLDialect) {
      // HyperSQL's character POSITION/LOCATE uses String.indexOf, independently of collation.
      // Length excludes prefix matches and prevents SQL's usual trailing-space padding.
      exact =
          "locate(:repo, c.repositoryName) = 1 AND length(c.repositoryName) = length(:repo)";
    } else if (dialect instanceof PostgreSQLDialect) {
      exact =
          "sql('convert_to(?, ''UTF8'')', c.repositoryName)"
              + " = sql('convert_to(?, ''UTF8'')', :repo)";
    } else if (dialect instanceof SQLServerDialect) {
      // BIN2 preserves Unicode identity; SQL Server still pads string comparisons with spaces,
      // so compare stored byte lengths as well (LEN would discard trailing spaces).
      exact =
          "sql('? collate Latin1_General_100_BIN2', c.repositoryName)"
              + " = sql('? collate Latin1_General_100_BIN2', :repo)"
              + " AND sql('datalength(?)', c.repositoryName) = sql('datalength(?)', :repo)";
    } else {
      throw new IllegalStateException(
          "Exact structured repository queries require a supported database dialect: "
              + dialect.getClass().getName());
    }
    // Keep the ordinary equality as an indexable prefilter. The exact condition remains in SQL
    // so offsets, limits, ordering and forward-only cursors retain their existing boundaries.
    return "c.repositoryName = :repo AND (" + exact + ")";
  }
}
