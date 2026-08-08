/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** Repository-scoped fail-closed guard for semantic Search indexing profiles. */
final class SearchIndexProfileCompatibility {

  private static final Map<SessionFactory, Set<String>> VERIFIED_REPOSITORIES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private SearchIndexProfileCompatibility() {}

  static void requireCompatible(SessionFactory sessionFactory, String repositoryName) {
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    Objects.requireNonNull(repositoryName, "repositoryName");
    SearchIndexingProfile configured = SearchIndexingProfile.resolve(sessionFactory);
    String verificationKey = repositoryName + "\u0000" + configured.id();

    synchronized (VERIFIED_REPOSITORIES) {
      Set<String> verified =
          VERIFIED_REPOSITORIES.computeIfAbsent(sessionFactory, ignored -> new HashSet<>());
      if (verified.contains(verificationKey)) {
        return;
      }

      if (hasIncompatibleSearchDocument(sessionFactory, repositoryName, configured.id())) {
        throw new SearchIndexProfileMismatchException(
            repositoryName, configured.id(), persistedProfiles(sessionFactory, repositoryName));
      }
      verified.add(verificationKey);
    }
  }

  /**
   * Check the derived Search index rather than the relational projection on the normal path.
   *
   * <p>This keeps the guard out of Hibernate SQL/query counters while still detecting a profile
   * change back to the implicit default. Documents created before profile-aware mapping also match
   * this query because they do not contain the current {@code indexProfile} field and therefore do
   * not match the configured-profile predicate.
   */
  private static boolean hasIncompatibleSearchDocument(
      SessionFactory sessionFactory, String repositoryName, String configuredProfile) {
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return !searchSession
          .search(GitCommitIndex.class)
          .select(f -> f.id(String.class))
          .where(
              f ->
                  f.bool()
                      .filter(f.match().field("repositoryName").matching(repositoryName))
                      .mustNot(f.match().field("indexProfile").matching(configuredProfile)))
          .fetchHits(1)
          .isEmpty();
    }
  }

  /** Load relational profile IDs only for the fail-closed diagnostic path. */
  private static Set<String> persistedProfiles(
      SessionFactory sessionFactory, String repositoryName) {
    try (Session session = sessionFactory.openSession()) {
      return new HashSet<>(
          session
              .createQuery(
                  "SELECT DISTINCT c.indexProfile FROM GitCommitIndex c "
                      + "WHERE c.repositoryName = :repo",
                  String.class)
              .setParameter("repo", repositoryName)
              .getResultList());
    }
  }
}
