/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionParticipant;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.internal.ExactRepositoryProjectionDeletion;
import org.hibernate.Session;

/** Removes Hibernate Search commit projections in the core repository deletion transaction. */
public final class SearchRepositoryDeletionParticipant
    implements RepositoryDeletionParticipant {

  private static final int PAGE_SIZE = 256;

  @Override
  public int deleteRepository(Session session, RepositoryName repositoryName) {
    int removed = 0;
    String after = null;
    while (true) {
      var page = ExactRepositoryProjectionDeletion.deletePage(
          session, repositoryName.value(), after, PAGE_SIZE);
      removed = Math.addExact(removed, page.removedRows());
      if (page.scannedRows() < PAGE_SIZE) {
        return removed;
      }
      after = page.lastProjectionKey();
    }
  }
}
