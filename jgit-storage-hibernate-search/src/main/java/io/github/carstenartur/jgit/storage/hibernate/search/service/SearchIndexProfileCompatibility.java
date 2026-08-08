/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Repository-scoped fail-closed guard for semantic Search indexing profiles. */
final class SearchIndexProfileCompatibility {

  private static final Map<SessionFactory, Set<String>> VERIFIED_REPOSITORIES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private SearchIndexProfileCompatibility() {}

  static void requireCompatible(SessionFactory sessionFactory, String repositoryName) {
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    Objects.requireNonNull(repositoryName, "repositoryName");

    /*
     * An omitted property means the historical CONTENT semantics. The migration backfills every
     * existing projection to exactly that stable profile, so probing here would add one SQL query to
     * every existing application's first Search request without protecting against an operator
     * initiated profile change. Explicit configuration opts into the fail-closed profile contract.
     */
    Object configuredProperty =
        sessionFactory.getProperties().get(SearchIndexingProfile.PROFILE_PROPERTY);
    if (configuredProperty == null || configuredProperty.toString().isBlank()) {
      return;
    }

    SearchIndexingProfile configured = SearchIndexingProfile.resolve(sessionFactory);
    String verificationKey = repositoryName + "\u0000" + configured.id();

    synchronized (VERIFIED_REPOSITORIES) {
      Set<String> verified =
          VERIFIED_REPOSITORIES.computeIfAbsent(sessionFactory, ignored -> new HashSet<>());
      if (verified.contains(verificationKey)) {
        return;
      }

      Set<String> persisted = persistedProfiles(sessionFactory, repositoryName);
      if (!persisted.isEmpty()
          && (persisted.size() != 1 || !persisted.contains(configured.id()))) {
        throw new SearchIndexProfileMismatchException(repositoryName, configured.id(), persisted);
      }
      verified.add(verificationKey);
    }
  }

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
