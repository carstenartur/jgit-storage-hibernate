/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitObjectEntity;
import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitRefEntity;
import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitReflogEntity;
import java.util.Properties;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/** Utility methods for configuring Hibernate ORM for the core Git storage tables. */
public final class HibernateGitStorageConfiguration {

  private HibernateGitStorageConfiguration() {}

  public static SessionFactory buildSessionFactory(Properties properties) {
    Configuration cfg = new Configuration();
    cfg.addProperties(properties);
    registerCoreEntities(cfg);
    return cfg.buildSessionFactory();
  }

  public static void registerCoreEntities(Configuration cfg) {
    cfg.addAnnotatedClass(GitObjectEntity.class);
    cfg.addAnnotatedClass(GitRefEntity.class);
    cfg.addAnnotatedClass(GitPackEntity.class);
    cfg.addAnnotatedClass(GitReflogEntity.class);
  }
}
