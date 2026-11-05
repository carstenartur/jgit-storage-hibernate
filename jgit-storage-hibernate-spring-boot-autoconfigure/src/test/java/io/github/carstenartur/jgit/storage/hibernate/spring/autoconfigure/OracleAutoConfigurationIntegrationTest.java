/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import oracle.jdbc.pool.OracleDataSource;
import org.eclipse.jgit.lib.Constants;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/** Proves that starter-managed Oracle migrations run before Hibernate validation. */
@Testcontainers(disabledWithoutDocker = true)
class OracleAutoConfigurationIntegrationTest {

  @Container
  static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23.26.3-slim-faststart")
          .withStartupTimeout(Duration.ofMinutes(5));

  @TempDir Path temporaryDirectory;

  @Test
  void migratesCoreAndSearchThenValidatesAndReopensTheApplication() throws Exception {
    DataSource dataSource = dataSource();
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(JgitStorageHibernateAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withPropertyValues(
                "jgit.storage.hibernate.search.enabled=true",
                "jgit.storage.hibernate.search.directory=" + temporaryDirectory.resolve("lucene"));

    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          context.getBean(JgitStorageSchemaManager.class).initialize();
          JgitRepositoryService repositories = context.getBean(JgitRepositoryService.class);
          repositories.create("oracle-starter");
          assertEquals(List.of("oracle-starter"), repositories.list());
          assertEquals(
              0,
              context.getBean(GitHistorySearchService.class).countIndexedCommits("oracle-starter"));
          assertNotNull(
              context.getBean(
                  JgitStorageHibernateAutoConfiguration.SESSION_FACTORY_BEAN,
                  SessionFactory.class));
        });

    runner.withPropertyValues("jgit.storage.hibernate.schema-action=validate")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              JgitRepositoryService repositories = context.getBean(JgitRepositoryService.class);
              assertEquals(List.of("oracle-starter"), repositories.list());
              try (HibernateGitStorage storage = repositories.open("oracle-starter")) {
                assertEquals(
                    "refs/heads/main",
                    storage.repository().exactRef(Constants.HEAD).getTarget().getName());
              }
              assertEquals(
                  0,
                  context.getBean(GitHistorySearchService.class).countIndexedCommits("oracle-starter"));
            });
  }

  private static DataSource dataSource() throws SQLException {
    OracleDataSource dataSource = new OracleDataSource();
    dataSource.setURL(ORACLE.getJdbcUrl());
    dataSource.setUser(ORACLE.getUsername());
    dataSource.setPassword(ORACLE.getPassword());
    return dataSource;
  }
}
