/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import io.github.carstenartur.jgit.storage.hibernate.search.RepositoryRoutingMappingConfigurer;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** One-time guards for persistent document identifiers and opt-in repository routing. */
final class SearchIndexCompatibility {

  private static final String DIRECTORY_TYPE_PROPERTY = "hibernate.search.backend.directory.type";
  private static final String PERSISTENT_DIRECTORY_TYPE = "local-filesystem";
  private static final String LEGACY_PROJECTION_PATTERN = "legacy-%";
  private static final int ROUTING_COUNT_PAGE_SIZE = 256;

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
    if (!usesPersistentLocalFilesystem(sessionFactory)
        || RepositoryRoutingMappingConfigurer.isEnabled(sessionFactory)) {
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

  /**
   * Check the opt-in index before serving reads. Routing and shard layout changes require an offline
   * reindex; an ordinary match-all count alone cannot detect documents on the wrong shard.
   */
  static void requireCurrentRepositoryRouting(SessionFactory sessionFactory) {
    synchronized (VERIFIED_FACTORIES) {
      if (VERIFIED_FACTORIES.contains(sessionFactory)) {
        return;
      }
      try (Session session = sessionFactory.openSession()) {
        Map<String, Long> rows = exactRepositoryCounts(session);
        SearchSession search = Search.session(session);
        search.workspace(GitCommitIndex.class).refresh();
        long expectedTotal = rows.values().stream().mapToLong(Long::longValue).sum();
        long actualTotal =
            search.search(GitCommitIndex.class).where(f -> f.matchAll()).fetchTotalHitCount();
        if (actualTotal != expectedTotal) {
          throw routingMismatch(
              "index contains " + actualTotal + " documents; expected " + expectedTotal);
        }
        for (Map.Entry<String, Long> row : rows.entrySet()) {
          long routed =
              search
                  .search(GitCommitIndex.class)
                  .where(f -> f.match().field("repositoryName").matching(row.getKey()))
                  .routing(row.getKey())
                  .fetchTotalHitCount();
          if (routed != row.getValue()) {
            throw routingMismatch(
                "repository '"
                    + row.getKey()
                    + "' has "
                    + routed
                    + " of "
                    + row.getValue()
                    + " expected routed documents");
          }
        }
      }
      VERIFIED_FACTORIES.add(sessionFactory);
    }
  }

  private static Map<String, Long> exactRepositoryCounts(Session session) {
    // SQL grouping follows the database collation, while Search keyword fields and routes use
    // exact repository names. Count scalar names in Java without hydrating entities or retaining
    // all projections: memory is one page plus one counter per exact repository identity.
    Map<String, Long> counts = new HashMap<>();
    String after = null;
    while (true) {
      var query =
          session
              .createSelectionQuery(
                  "SELECT c.projectionKey, c.repositoryName FROM GitCommitIndex c"
                      + (after == null ? "" : " WHERE c.projectionKey > :after")
                      + " ORDER BY c.projectionKey",
                  RepositoryProjection.class)
              .setMaxResults(ROUTING_COUNT_PAGE_SIZE);
      if (after != null) {
        query.setParameter("after", after);
      }
      List<RepositoryProjection> page = query.getResultList();
      for (RepositoryProjection row : page) {
        counts.merge(row.repository(), 1L, Long::sum);
      }
      if (page.size() < ROUTING_COUNT_PAGE_SIZE) {
        return counts;
      }
      // The primary key is unique under the same database comparison used by ORDER BY and >.
      // Its keyset therefore remains strict even when that comparison is case-insensitive.
      after = page.getLast().projectionKey();
    }
  }

  private static IllegalStateException routingMismatch(String mismatch) {
    return new IllegalStateException(
        "Repository-routed commit Search index is not ready: " + mismatch
            + ". Stop projection writers, use the configured shard layout in a fresh index root, "
            + "and run the GitCommitIndex Hibernate Search MassIndexer before serving reads.");
  }

  private record RepositoryProjection(String projectionKey, String repository) {}

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
