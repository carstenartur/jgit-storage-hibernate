/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;

/** Reference database using JGit DFS reftables persisted through the Hibernate object database. */
public class HibernateRefDatabase extends DfsReftableDatabase {

  /**
   * Create a reference database.
   *
   * @param repository owning DFS repository
   */
  public HibernateRefDatabase(DfsRepository repository) {
    super(repository);
  }

  @Override
  public ReftableConfig getReftableConfig() {
    ReftableConfig config = new ReftableConfig();
    config.setAlignBlocks(false);
    config.setIndexObjects(false);
    config.fromConfig(getRepository().getConfig());
    return config;
  }

  @Override
  public boolean performsAtomicTransactions() {
    return true;
  }
}
