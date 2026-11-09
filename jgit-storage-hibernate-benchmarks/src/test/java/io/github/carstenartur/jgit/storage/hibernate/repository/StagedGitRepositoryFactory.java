/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import io.github.carstenartur.jgit.storage.hibernate.objects.StagedGitPublicationExperiment;
import java.io.IOException;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.hibernate.SessionFactory;

/** Benchmark-only bridge to the package-private repository construction seam. */
public final class StagedGitRepositoryFactory {
  private StagedGitRepositoryFactory() {}

  public static HibernateRepository open(
      SessionFactory factory, String name, StagedGitPublicationExperiment experiment)
      throws IOException {
    var builder =
        new HibernateRepositoryBuilder().setSessionFactory(factory).setRepositoryName(name);
    builder.setReaderOptions(new DfsReaderOptions());
    return new HibernateRepository(builder, experiment::database);
  }
}
