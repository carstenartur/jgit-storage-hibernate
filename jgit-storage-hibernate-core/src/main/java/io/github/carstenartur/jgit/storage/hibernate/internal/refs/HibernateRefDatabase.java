/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.refs;

import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;

/** Ref database backed by JGit DFS reftable files stored through {@code HibernateObjDatabase}. */
public class HibernateRefDatabase extends DfsReftableDatabase {

  public HibernateRefDatabase(DfsRepository repo) {
    super(repo);
  }

  @Override
  public ReftableConfig getReftableConfig() {
    ReftableConfig cfg = new ReftableConfig();
    cfg.setAlignBlocks(false);
    cfg.setIndexObjects(false);
    cfg.fromConfig(getRepository().getConfig());
    return cfg;
  }

  @Override
  public boolean performsAtomicTransactions() {
    return true;
  }
}
