/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.internal;

import io.github.carstenartur.jgit.storage.hibernate.search.RepositoryRoutingMappingConfigurer;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.util.List;
import java.util.Objects;
import org.hibernate.Session;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.cfg.HibernateOrmMapperSettings;

/** Internal scalar-page deletion shared by repository removal and projection rebuilds. */
public final class ExactRepositoryProjectionDeletion {

  private ExactRepositoryProjectionDeletion() {}

  /**
   * Delete one page in the caller's active transaction, preserving exact repository identity.
   *
   * <p>SQL equality can include case/collation aliases. Filter their scalar names in Java and only
   * delete the selected unique projection keys. Advance through all candidates, even a page that
   * contains only aliases, so retained neighbors neither stop nor repeat the scan.
   */
  public static Page deletePage(
      Session session, String repositoryName, String after, int pageSize) {
    var query = session.createSelectionQuery(
        "SELECT c.projectionKey, c.repositoryName FROM GitCommitIndex c "
            + "WHERE c.repositoryName = :repo"
            + (after == null ? "" : " AND c.projectionKey > :after")
            + " ORDER BY c.projectionKey",
        ProjectionIdentity.class)
        .setParameter("repo", repositoryName)
        .setMaxResults(pageSize);
    if (after != null) {
      query.setParameter("after", after);
    }
    List<ProjectionIdentity> candidates = query.getResultList();
    List<String> keys = candidates.stream()
        .filter(row -> repositoryName.equals(row.repositoryName()))
        .map(ProjectionIdentity::projectionKey)
        .toList();
    int removed = 0;
    if (!keys.isEmpty()) {
      removed = session.createMutationQuery(
          "DELETE FROM GitCommitIndex c WHERE c.projectionKey IN :keys")
          .setParameter("keys", keys)
          .executeUpdate();
      if (Boolean.parseBoolean(Objects.toString(session.getSessionFactory().getProperties()
          .get(HibernateOrmMapperSettings.ENABLED), "true"))) {
        String route = RepositoryRoutingMappingConfigurer.isEnabled(session.getSessionFactory())
            ? repositoryName : null;
        var indexingPlan = Search.session(session).indexingPlan();
        for (String key : keys) {
          indexingPlan.purge(GitCommitIndex.class, key, route);
        }
        // Prepare compact delete work without executing it before the SQL transaction commits.
        // The atomic repository-deletion transaction can retain these keys until its final commit.
        indexingPlan.process();
      }
    }
    return new Page(candidates.size(), removed,
        candidates.isEmpty() ? after : candidates.getLast().projectionKey());
  }

  public record Page(int scannedRows, int removedRows, String lastProjectionKey) {}

  private record ProjectionIdentity(String projectionKey, String repositoryName) {}
}
