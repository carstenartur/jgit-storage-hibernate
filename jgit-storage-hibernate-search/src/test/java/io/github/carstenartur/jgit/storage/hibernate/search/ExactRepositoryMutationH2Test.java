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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionParticipant;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
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

class ExactRepositoryMutationH2Test {

  private static final String SELECTED = "team/beta";
  private static final String NEIGHBOR = "Team/Beta";

  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rebuildPreservesCaseDistinctRepositoryProjections(boolean routing) throws Exception {
    try (var provider = provider(routing);
        var selected = repository("selected");
        var neighbor = repository("neighbor")) {
      var factory = provider.getSessionFactory();
      indexHistory(factory, selected.getRepository(), SELECTED, 3);
      indexHistory(factory, neighbor.getRepository(), NEIGHBOR, 260);
      assertCaseInsensitiveFixture(factory);
      factory.getStatistics().clear();

      // Both public APIs accept an external JGit repository plus its exact logical name. These
      // sources have no refs, so rebuilding the selected source only clears its old projection.
      var result = new CommitProjectionRebuilder(factory)
          .rebuild(selected.getRepository(), new RepositoryName(SELECTED));

      assertAll(
          () -> assertEquals(3, result.removedProjections()),
          () -> assertEquals(0, exactSqlCount(factory, SELECTED)),
          () -> assertEquals(260, exactSqlCount(factory, NEIGHBOR)),
          () -> assertEquals(0, searchCount(factory, SELECTED)),
          () -> assertEquals(260, searchCount(factory, NEIGHBOR)),
          () -> assertEquals(0, factory.getStatistics()
              .getEntityStatistics(GitCommitIndex.class.getName()).getLoadCount(),
              "projection deletion must not hydrate large projection fields"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repositoryDeletionPreservesCaseDistinctRepositoryProjections(boolean routing) throws Exception {
    try (var provider = provider(routing);
        var selected = repository("selected");
        var neighbor = repository("neighbor")) {
      var factory = provider.getSessionFactory();
      indexHistory(factory, selected.getRepository(), SELECTED, 3);
      indexHistory(factory, neighbor.getRepository(), NEIGHBOR, 260);
      assertCaseInsensitiveFixture(factory);
      factory.getStatistics().clear();

      var repositories = new DefaultHibernateRepositoryFactory(
          factory, List.of(new SearchRepositoryDeletionParticipant()));
      var result = repositories.deleteRepository(new RepositoryName(SELECTED));

      assertAll(
          () -> assertEquals(3, result.projectionRows()),
          () -> assertEquals(0, exactSqlCount(factory, SELECTED)),
          () -> assertEquals(260, exactSqlCount(factory, NEIGHBOR)),
          () -> assertEquals(0, searchCount(factory, SELECTED)),
          () -> assertEquals(260, searchCount(factory, NEIGHBOR)),
          () -> assertEquals(0, factory.getStatistics()
              .getEntityStatistics(GitCommitIndex.class.getName()).getLoadCount(),
              "projection deletion must not hydrate large projection fields"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void laterParticipantFailureRollsBackSqlAndSearchDeletion(boolean routing) throws Exception {
    try (var provider = provider(routing);
        var selected = repository("selected");
        var neighbor = repository("neighbor")) {
      var factory = provider.getSessionFactory();
      indexHistory(factory, selected.getRepository(), SELECTED, 3);
      indexHistory(factory, neighbor.getRepository(), NEIGHBOR, 260);
      assertCaseInsensitiveFixture(factory);
      var injectedFailure = new IllegalStateException("failure after Search projection deletion");
      RepositoryDeletionParticipant failure = (session, repositoryName) -> {
        session.flush();
        throw injectedFailure;
      };
      var repositories = new DefaultHibernateRepositoryFactory(factory,
          List.of(new SearchRepositoryDeletionParticipant(), failure));

      var failureResult = assertThrows(HibernateStorageException.class,
          () -> repositories.deleteRepository(new RepositoryName(SELECTED)));
      assertSame(injectedFailure, failureResult.getCause());
      assertAll(
          () -> assertEquals(3, exactSqlCount(factory, SELECTED)),
          () -> assertEquals(260, exactSqlCount(factory, NEIGHBOR)),
          () -> assertEquals(3, searchCount(factory, SELECTED)),
          () -> assertEquals(260, searchCount(factory, NEIGHBOR)));
    }
  }

  private Git repository(String name) throws Exception {
    return Git.init().setBare(true).setDirectory(directory.resolve(name).toFile()).call();
  }

  private static void indexHistory(
      SessionFactory factory, Repository repository, String name, int count) throws Exception {
    ObjectId tip = null;
    try (var inserter = repository.newObjectInserter()) {
      ObjectId tree = inserter.insert(new TreeFormatter());
      PersonIdent actor = new PersonIdent("Mutation isolation", "isolation@example.invalid");
      for (int i = 0; i < count; i++) {
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        commit.setAuthor(actor);
        commit.setCommitter(actor);
        commit.setMessage(name + " mutation isolation " + i);
        if (tip != null) {
          commit.setParentId(tip);
        }
        tip = inserter.insert(commit);
      }
      inserter.flush();
    }
    assertEquals(count, new CommitIndexer(factory, name).indexCommitsFrom(repository, tip, -1));
  }

  private static void assertCaseInsensitiveFixture(SessionFactory factory) {
    try (Session session = factory.openSession()) {
      assertEquals(1L, session.createSelectionQuery(
          "SELECT COUNT(DISTINCT c.repositoryName) FROM GitCommitIndex c", Long.class)
          .getSingleResult());
    }
    assertEquals(3, exactSqlCount(factory, SELECTED));
    assertEquals(260, exactSqlCount(factory, NEIGHBOR));
    assertEquals(3, searchCount(factory, SELECTED));
    assertEquals(260, searchCount(factory, NEIGHBOR));
  }

  private static long exactSqlCount(SessionFactory factory, String name) {
    try (Session session = factory.openSession()) {
      return session.createSelectionQuery(
          "SELECT c.repositoryName FROM GitCommitIndex c", String.class)
          .getResultList().stream().filter(name::equals).count();
    }
  }

  private static long searchCount(SessionFactory factory, String name) {
    try (Session session = factory.openSession()) {
      return Search.session(session).search(GitCommitIndex.class)
          .where(f -> f.match().field("repositoryName").matching(name)).fetchTotalHitCount();
    }
  }

  private static HibernateSessionFactoryProvider provider(boolean routing) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url",
        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;IGNORECASE=TRUE");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.put("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    properties.put(CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, 32);
    properties.put(CommitProjectionRebuilder.PURGE_BATCH_SIZE_PROPERTY, 64);
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
}
