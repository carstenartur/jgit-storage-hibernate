/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitBlobIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** Small query API for optional Hibernate Search projections. */
public class GitHistorySearchService {
  private final Session session;

  public GitHistorySearchService(Session session) {
    this.session = session;
  }

  public List<GitCommitIndex> searchCommits(String repositoryName, String text, int limit) {
    SearchSession searchSession = Search.session(session);
    return searchSession
        .search(GitCommitIndex.class)
        .where(
            f ->
                f.bool()
                    .must(f.match().field("repositoryName").matching(repositoryName))
                    .must(f.match().field("message").matching(text)))
        .fetchHits(limit);
  }

  public List<GitBlobIndex> searchBlobs(String repositoryName, String text, int limit) {
    SearchSession searchSession = Search.session(session);
    return searchSession
        .search(GitBlobIndex.class)
        .where(
            f ->
                f.bool()
                    .must(f.match().field("repositoryName").matching(repositoryName))
                    .must(f.match().fields("path", "content").matching(text)))
        .fetchHits(limit);
  }
}
