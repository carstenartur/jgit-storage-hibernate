/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExactRepositoryIndexingH2Test {

  private static final String OWNER = "Team/Beta";
  private static final String REQUESTED = "team/beta";

  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void batchLookupRejectsAnAliasInsteadOfCertifyingItsCommit(boolean routing) throws Exception {
    try (var repository = new InMemoryRepository(new DfsRepositoryDescription("external"))) {
      ObjectId shared = commit(repository, "Shared commit");
      ProjectionSnapshot original = seedOriginal(repository, shared, routing);
      ObjectId newTip = commit(repository, "Requested repository tip", shared);
      try (var provider = provider("validate", SearchIndexingProfile.CONTENT, routing)) {
        var factory = provider.getSessionFactory();
        assertCollatedLookupFindsForeignName(factory, shared);
        var indexer = new CommitIndexer(factory, REQUESTED);

        // Both commits fit in one batch. A foreign SQL hit must not be skipped, publish the other
        // projection or produce a completed-tip certificate for the requested exact name.
        assertAll(
            () -> assertCollision(assertThrows(IllegalStateException.class,
                () -> indexer.indexReachableCommits(repository, List.of(newTip), List.of()))),
            () -> assertOriginalUnchanged(factory, original));
        assertExactNameOperationsStillWork(factory, repository, original);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void upsertRejectsAnAliasBeforeChangingItsProjectionOrSearchDocument(boolean routing)
      throws Exception {
    try (var repository = new InMemoryRepository(new DfsRepositoryDescription("external"))) {
      ObjectId shared = commit(repository, "Shared commit");
      ProjectionSnapshot original = seedOriginal(repository, shared, routing);
      try (var provider = provider("validate", SearchIndexingProfile.CONTENT, routing)) {
        var factory = provider.getSessionFactory();
        assertCollatedLookupFindsForeignName(factory, shared);
        var indexer = new CommitIndexer(factory, REQUESTED);

        // The original was publicly indexed with PATHS, so an accidental CONTENT upsert would
        // observably change both its SQL profile/content and its Lucene document.
        assertAll(
            () -> assertCollision(assertThrows(IllegalStateException.class,
                () -> indexer.indexCommit(repository, shared))),
            () -> assertOriginalUnchanged(factory, original));
        assertExactNameOperationsStillWork(factory, repository, original);
      }
    }
  }

  private ProjectionSnapshot seedOriginal(Repository repository, ObjectId shared, boolean routing)
      throws Exception {
    // Search accepts an external JGit repository and a separate logical name. Two logical names
    // may expose identical Git objects (for example clones) without creating Core catalog aliases.
    try (var provider = provider("create", SearchIndexingProfile.PATHS, routing)) {
      var factory = provider.getSessionFactory();
      new CommitIndexer(factory, OWNER).indexCommit(repository, shared);
      return snapshots(factory).getFirst();
    }
  }

  private static void assertCollision(IllegalStateException failure) {
    assertTrue(failure.getMessage().contains("different repository name"), failure::getMessage);
  }

  private static void assertCollatedLookupFindsForeignName(SessionFactory factory, ObjectId shared) {
    try (Session session = factory.openSession()) {
      assertEquals(OWNER, session.createSelectionQuery(
          "SELECT c.repositoryName FROM GitCommitIndex c "
              + "WHERE c.repositoryName = :name AND c.objectId = :objectId", String.class)
          .setParameter("name", REQUESTED)
          .setParameter("objectId", shared.name())
          .getSingleResult());
    }
  }

  private static void assertOriginalUnchanged(SessionFactory factory, ProjectionSnapshot original) {
    assertAll(
        () -> assertEquals(List.of(original), snapshots(factory)),
        () -> assertEquals(1, searchCount(factory, OWNER, SearchIndexingProfile.PATHS)),
        () -> assertEquals(0, searchCount(factory, OWNER, SearchIndexingProfile.CONTENT)),
        () -> assertEquals(0, searchCount(factory, REQUESTED, null)));
  }

  private static void assertExactNameOperationsStillWork(
      SessionFactory factory, Repository repository, ProjectionSnapshot original) throws Exception {
    ObjectId independent = commit(repository, "Independent requested commit");
    var indexer = new CommitIndexer(factory, REQUESTED);
    assertEquals(1, indexer.indexCommitsFrom(repository, independent, -1));
    assertEquals(0, indexer.indexCommitsFrom(repository, independent, -1));
    indexer.indexCommit(repository, independent);
    assertEquals(2, snapshots(factory).size());
    assertEquals(List.of(original), snapshots(factory).stream()
        .filter(row -> OWNER.equals(row.repositoryName())).toList());
    assertEquals(1, searchCount(factory, OWNER, SearchIndexingProfile.PATHS));
    assertEquals(1, searchCount(factory, REQUESTED, SearchIndexingProfile.CONTENT));
  }

  private static List<ProjectionSnapshot> snapshots(SessionFactory factory) {
    try (Session session = factory.openSession()) {
      return session.createSelectionQuery("FROM GitCommitIndex c ORDER BY c.projectionKey",
          GitCommitIndex.class).getResultList().stream().map(ProjectionSnapshot::of).toList();
    }
  }

  private static long searchCount(
      SessionFactory factory, String name, SearchIndexingProfile profile) {
    try (Session session = factory.openSession()) {
      return Search.session(session).search(GitCommitIndex.class).where(f -> {
        var predicate = f.bool().must(f.match().field("repositoryName").matching(name));
        if (profile != null) {
          predicate.must(f.match().field("indexProfile").matching(profile.id()));
        }
        return predicate;
      }).fetchTotalHitCount();
    }
  }

  private static ObjectId commit(Repository repository, String message, ObjectId... parents)
      throws Exception {
    try (var inserter = repository.newObjectInserter()) {
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB,
          "indexer isolation content".getBytes(StandardCharsets.UTF_8));
      var tree = new TreeFormatter();
      tree.append("shared.txt", FileMode.REGULAR_FILE, blob);
      var commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      commit.setParentIds(parents);
      var actor = new PersonIdent("Indexer isolation", "isolation@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId result = inserter.insert(commit);
      inserter.flush();
      return result;
    }
  }

  private HibernateSessionFactoryProvider provider(
      String schemaAction, SearchIndexingProfile profile, boolean routing) {
    var properties = new Properties();
    properties.put("hibernate.connection.url",
        "jdbc:h2:file:" + directory.resolve("database/search") + ";IGNORECASE=TRUE");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.hbm2ddl.auto", schemaAction);
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-filesystem");
    properties.put("hibernate.search.backend.directory.root", directory.resolve("index").toString());
    properties.put("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    properties.put(SearchIndexingProfile.PROFILE_PROPERTY, profile.id());
    properties.put(CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, 2);
    if (routing) {
      properties.put("hibernate.search.mapping.configurer",
          RepositoryRoutingMappingConfigurer.class.getName());
      String prefix = "hibernate.search.backend.indexes."
          + RepositoryRoutingMappingConfigurer.INDEX_NAME + ".sharding.";
      properties.put(prefix + "strategy", "hash");
      properties.put(prefix + "number_of_shards", 2);
    }
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }

  private record ProjectionSnapshot(String key, String repositoryName, String objectId,
      String profile, String shortMessage, String fullMessage, String changedPaths, String changedText) {
    static ProjectionSnapshot of(GitCommitIndex row) {
      return new ProjectionSnapshot(row.getProjectionKey(), row.getRepositoryName(), row.getObjectId(),
          row.getIndexProfile(), row.getShortMessage(), row.getFullMessage(), row.getChangedPaths(),
          row.getChangedText());
    }
  }
}
