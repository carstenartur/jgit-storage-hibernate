/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.Repository;

/** Default {@link HibernateGitStorage} implementation wrapping a JGit repository. */
public final class DefaultHibernateGitStorage implements HibernateGitStorage {

  private final Repository repository;
  private final Runnable afterClose;
  private final AtomicBoolean open = new AtomicBoolean(true);

  /**
   * Create a storage facade.
   *
   * @param repository repository to expose and close
   */
  public DefaultHibernateGitStorage(Repository repository) {
    this(repository, () -> {});
  }

  /**
   * Create a storage facade with a lifecycle callback.
   *
   * @param repository repository to expose and close
   * @param afterClose callback invoked exactly once after the repository closes
   */
  public DefaultHibernateGitStorage(Repository repository, Runnable afterClose) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.afterClose = Objects.requireNonNull(afterClose, "afterClose");
  }

  @Override
  public Repository repository() {
    return repository;
  }

  @Override
  public void close() {
    if (open.compareAndSet(true, false)) {
      try {
        repository.close();
      } finally {
        afterClose.run();
      }
    }
  }
}
