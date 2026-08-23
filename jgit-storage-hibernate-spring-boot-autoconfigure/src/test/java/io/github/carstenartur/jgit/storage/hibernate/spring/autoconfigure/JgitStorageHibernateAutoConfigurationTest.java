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

import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
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
          assertEquals(java.util.List.of("configured"), repositories.list());
          repositories.create("second");
          assertEquals(java.util.List.of("configured", "second"), repositories.list());
        });
  }

  private static DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:spring-autoconfigure;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    return dataSource;
  }
}
