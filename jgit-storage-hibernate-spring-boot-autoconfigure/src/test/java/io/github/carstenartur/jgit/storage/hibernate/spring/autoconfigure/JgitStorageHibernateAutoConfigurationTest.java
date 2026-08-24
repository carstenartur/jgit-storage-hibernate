/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import javax.sql.DataSource;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JgitStorageHibernateAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(JgitStorageHibernateAutoConfiguration.class))
          .withBean(DataSource.class, JgitStorageHibernateAutoConfigurationTest::dataSource)
          .withPropertyValues(
              "jgit.storage.hibernate.search.enabled=false",
              "jgit.storage.hibernate.repositories[0]=configured");

  @Test
  void createsDedicatedRepositoryBeansAndBootstrapsConfiguredRepositories() {
    contextRunner.run(
        context -> {
          assertNotNull(
              context.getBean(JgitStorageHibernateAutoConfiguration.SESSION_FACTORY_BEAN));
          JgitRepositoryService repositories = context.getBean(JgitRepositoryService.class);
          assertDoesNotThrow(
              () ->
                  context
                      .getBean("jgitConfiguredRepositoryBootstrap", ApplicationRunner.class)
                      .run(new DefaultApplicationArguments()));
          assertEquals(java.util.List.of("configured"), repositories.list());
          assertEquals(Constants.R_HEADS + "main", assertDoesNotThrow(() -> headTarget(repositories, "configured")));

          repositories.create("second");
          assertEquals(java.util.List.of("configured", "second"), repositories.list());
          assertEquals(Constants.R_HEADS + "main", assertDoesNotThrow(() -> headTarget(repositories, "second")));

          assertDoesNotThrow(() -> repositories.create("configured"));
          assertEquals(Constants.R_HEADS + "main", assertDoesNotThrow(() -> headTarget(repositories, "configured")));
        });
  }

  private static String headTarget(JgitRepositoryService repositories, String repositoryName)
      throws Exception {
    try (HibernateGitStorage storage = repositories.open(repositoryName)) {
      Ref head = storage.repository().exactRef(Constants.HEAD);
      assertNotNull(head);
      assertTrue(head.isSymbolic());
      return head.getTarget().getName();
    }
  }

  private static DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:spring-autoconfigure;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    return dataSource;
  }
}
