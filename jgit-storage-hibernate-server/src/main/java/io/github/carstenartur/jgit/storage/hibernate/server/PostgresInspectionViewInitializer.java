/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates read-only PostgreSQL views intended for integration-test and operator inspection. */
public final class PostgresInspectionViewInitializer implements ApplicationRunner {

  private final DataSource dataSource;
  private final JgitStorageServerProperties properties;

  public PostgresInspectionViewInitializer(
      DataSource dataSource, JgitStorageServerProperties properties) {
    this.dataSource = dataSource;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.isInspectionViewsEnabled()) {
      return;
    }
    requirePostgres();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE SCHEMA IF NOT EXISTS jsh_inspection");
    jdbc.execute(
        """
        CREATE OR REPLACE VIEW jsh_inspection.repository AS
        SELECT repository_name, created_at
          FROM git_repository_lifecycle
        """);
    jdbc.execute(
        """
        CREATE OR REPLACE VIEW jsh_inspection.reflog AS
        SELECT repository_name,
               delivery_id,
               ref_name,
               old_id,
               new_id,
               who_name,
               who_email,
               who_when,
               message
          FROM git_reflog
        """);
    jdbc.execute(
        """
        CREATE OR REPLACE VIEW jsh_inspection.commit_history AS
        SELECT repository_name,
               object_id,
               short_message,
               author_name,
               author_email,
               author_time,
               committer_name,
               committer_email,
               commit_time AS committer_time,
               changed_paths
          FROM git_commit_index
        """);
    jdbc.execute(
        """
        CREATE OR REPLACE VIEW jsh_inspection.commit_change AS
        SELECT history.repository_name,
               history.object_id,
               history.short_message,
               history.author_email,
               history.committer_email,
               history.committer_time,
               changed.path
          FROM jsh_inspection.commit_history history
          CROSS JOIN LATERAL unnest(
              string_to_array(COALESCE(history.changed_paths, ''), E'\n')) AS changed(path)
         WHERE changed.path <> ''
        """);
  }

  private void requirePostgres() {
    try (Connection connection = dataSource.getConnection()) {
      String product =
          connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (!product.contains("postgresql")) {
        throw new IllegalStateException(
            "The standalone inspection views currently require PostgreSQL but found " + product);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Could not validate the inspection database", exception);
    }
  }
}
