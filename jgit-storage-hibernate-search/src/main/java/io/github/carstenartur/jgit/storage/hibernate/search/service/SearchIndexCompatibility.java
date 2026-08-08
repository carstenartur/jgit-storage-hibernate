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
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** One-time compatibility guard for persistent Hibernate Search commit indexes. */
final class SearchIndexCompatibility {

  private static final String DIRECTORY_TYPE_PROPERTY = "hibernate.search.backend.directory.type";
  private static final String PERSISTENT_DIRECTORY_TYPE = "local-filesystem";
  private static final String LEGACY_PROJECTION_PATTERN = "legacy-%";

  /**
   * Session factories are application-owned and may be short-lived in tests, so keep only weak
   * references while remembering factories whose persistent index has already been checked.
   */
  private static final Set<SessionFactory> VERIFIED_FACTORIES =
      Collections.newSetFromMap(new WeakHashMap<>());

  private SearchIndexCompatibility() {}

  /**
   * Rebuild the derived commit index when a migrated relational projection cannot be addressed by
   * its current assigned document identifier.
   *
   * <p>Versions before the assigned {@code projection_key} mapping used the generated numeric
   * database identity as the Hibernate Search document identifier. The relational migration keeps
   * those rows and assigns stable {@code legacy-*} projection keys, but an already persisted Lucene
   * index still contains the old numeric identifiers. A single probe of one migrated row detects
   * that mismatch without hydrating entities. The mass indexer then purges and recreates the derived
   * index from the authoritative relational projections.
   *
   * <p>The check is deliberately limited to the persistent local-filesystem backend. In-memory
   * indexes cannot survive a mapping upgrade, and probing them would add an unnecessary SQL query to
   * ordinary reads and writes.
   */
  static void ensureCurrentDocumentIdentifiers(SessionFactory sessionFactory) {
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    if (!usesPersistentLocalFilesystem(sessionFactory)) {
      return;
    }
    synchronized (VERIFIED_FACTORIES) {
      if (VERIFIED_FACTORIES.contains(sessionFactory)) {
        return;
      }
      if (requiresRebuild(sessionFactory)) {
        rebuild(sessionFactory);
      }
      VERIFIED_FACTORIES.add(sessionFactory);
    }
  }

  private static boolean usesPersistentLocalFilesystem(SessionFactory sessionFactory) {
    Object configured = sessionFactory.getProperties().get(DIRECTORY_TYPE_PROPERTY);
    return configured != null && PERSISTENT_DIRECTORY_TYPE.equals(configured.toString().trim());
  }

  private static boolean requiresRebuild(SessionFactory sessionFactory) {
    try (Session session = sessionFactory.openSession()) {
      String migratedProjectionKey =
          session
              .createQuery(
                  "SELECT c.projectionKey FROM GitCommitIndex c "
                      + "WHERE c.projectionKey LIKE :legacyPattern ORDER BY c.projectionKey",
                  String.class)
              .setParameter("legacyPattern", LEGACY_PROJECTION_PATTERN)
              .setMaxResults(1)
              .uniqueResult();
      if (migratedProjectionKey == null) {
        return false;
      }

      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(GitCommitIndex.class)
          .select(f -> f.id(String.class))
          .where(f -> f.id().matching(migratedProjectionKey))
          .fetchHits(1)
          .isEmpty();
    }
  }

  private static void rebuild(SessionFactory sessionFactory) {
    try {
      Search.mapping(sessionFactory)
          .scope(GitCommitIndex.class)
          .massIndexer()
          .startAndWait();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while rebuilding an incompatible persistent Hibernate Search index",
          exception);
    }
  }
}
