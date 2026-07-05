/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Properties;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class HibernateGitStorageConfigurationTest {
  @Test
  void buildsSessionFactoryWithCoreEntities() {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    properties.setProperty("hibernate.connection.url", "jdbc:h2:mem:jgit-storage-test;DB_CLOSE_DELAY=-1");
    properties.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    try (SessionFactory sessionFactory = HibernateGitStorageConfiguration.buildSessionFactory(properties)) {
      assertNotNull(sessionFactory);
    }
  }
}
