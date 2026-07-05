/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.util.List;

/** Registry of optional search projection entities. */
public final class SearchEntities {

  private SearchEntities() {}

  /**
   * Return annotated entities required by the search module.
   *
   * @return immutable list of entity classes
   */
  public static List<Class<?>> annotatedClasses() {
    return List.of(GitCommitIndex.class);
  }
}
