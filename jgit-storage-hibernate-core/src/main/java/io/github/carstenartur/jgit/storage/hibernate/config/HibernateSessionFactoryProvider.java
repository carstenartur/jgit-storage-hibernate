/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/** Provides a Hibernate {@link SessionFactory} configured for core JGit database storage. */
public final class HibernateSessionFactoryProvider implements AutoCloseable {

  private final SessionFactory sessionFactory;

  public HibernateSessionFactoryProvider(Properties properties) {
    this(properties, List.of());
  }

  public HibernateSessionFactoryProvider(
      Properties properties, Collection<Class<?>> additionalAnnotatedClasses) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(additionalAnnotatedClasses, "additionalAnnotatedClasses");

    Configuration configuration = new Configuration();
    configuration.addProperties(properties);
    addCoreEntities(configuration);
    for (Class<?> annotatedClass : additionalAnnotatedClasses) {
      configuration.addAnnotatedClass(annotatedClass);
    }
    this.sessionFactory = configuration.buildSessionFactory();
  }

  public HibernateSessionFactoryProvider(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  private static void addCoreEntities(Configuration configuration) {
    configuration.addAnnotatedClass(GitPackEntity.class);
    configuration.addAnnotatedClass(GitReflogEntity.class);
  }

  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  @Override
  public void close() {
    if (!sessionFactory.isClosed()) {
      sessionFactory.close();
    }
  }
}
