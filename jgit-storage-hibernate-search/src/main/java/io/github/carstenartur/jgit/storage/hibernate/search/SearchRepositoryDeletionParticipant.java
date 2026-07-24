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
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.util.List;
import org.hibernate.Session;

/** Removes Hibernate Search commit projections in the core repository deletion transaction. */
public final class SearchRepositoryDeletionParticipant
    implements RepositoryDeletionParticipant {

  @Override
  public int deleteRepository(Session session, RepositoryName repositoryName) {
    List<GitCommitIndex> projections =
        session
            .createQuery(
                "FROM GitCommitIndex c WHERE c.repositoryName = :repo",
                GitCommitIndex.class)
            .setParameter("repo", repositoryName.value())
            .getResultList();
    projections.forEach(session::remove);
    return projections.size();
  }
}
