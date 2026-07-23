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
import java.util.List;

/**
 * Registry of the annotated entity classes required by the core storage backend.
 *
 * <p>Framework-managed Hibernate configurations, including Spring Boot/JPA applications, can add
 * these classes without scanning implementation packages.
 */
public final class CoreEntities {

  private static final List<Class<?>> ANNOTATED_CLASSES =
      List.of(GitPackEntity.class, GitReflogEntity.class);

  /**
   * Return the immutable list of annotated core entity classes.
   *
   * @return core entity classes in stable registration order
   */
  public static List<Class<?>> annotatedClasses() {
    return ANNOTATED_CLASSES;
  }

  private CoreEntities() {}
}
