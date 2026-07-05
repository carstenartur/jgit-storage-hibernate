/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.FilePathHistory;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitBlobIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import org.hibernate.cfg.Configuration;

/** Registers optional Hibernate Search projection entities. */
public final class GitHistorySearchConfiguration {
  private GitHistorySearchConfiguration() {}

  public static void registerSearchEntities(Configuration configuration) {
    configuration.addAnnotatedClass(GitCommitIndex.class);
    configuration.addAnnotatedClass(GitBlobIndex.class);
    configuration.addAnnotatedClass(FilePathHistory.class);
  }
}
