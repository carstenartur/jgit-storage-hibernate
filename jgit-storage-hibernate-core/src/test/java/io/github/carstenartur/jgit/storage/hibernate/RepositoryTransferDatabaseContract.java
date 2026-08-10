/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.flywaydb.core.Flyway;

final class RepositoryTransferDatabaseContract {

  private static final AtomicInteger CONTRACT_COUNTER = new AtomicInteger();

  private RepositoryTransferDatabaseContract() {}

  static void verify(DatabaseFixture database) throws Exception {
    migrate(database);
    restart(database);

    String suffix = database.name() + "-" + CONTRACT_COUNTER.incrementAndGet();
    RepositoryName sourceName = new RepositoryName("transfer-source-" + suffix);
    RepositoryName disposableTarget = new RepositoryName("transfer-disposable-" + suffix);
    RepositoryName survivingTarget = new RepositoryName("transfer-surviving-" + suffix);

    SourceGraph graph;
    try (HibernateSessionFactoryProvider provider = provider(database)) {
      DefaultHibernateRepositoryFactory repositories =
          new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
      try (HibernateGitStorage source = repositories.open(sourceName)) {
        graph = createSourceGraph(source.repository());
      }

      RepositoryTransferResult disposableClone =
          repositories.transfer(initialClone(sourceName, disposableTarget));
      RepositoryTransferResult survivingClone =
          repositories.transfer(initialClone(sourceName, survivingTarget));
      assertTrue(disposableClone.targetCreated());
      assertTrue(survivingClone.targetCreated());
      assertEquals(graph.merge(), disposableClone.refs().get("refs/heads/draft").targetObjectId());
      assertEquals(graph.tag(), disposableClone.refs().get("refs/tags/source-v1").targetObjectId());
      assertEquals(graph.merge(), survivingClone.refs().get("refs/heads/draft").targetObjectId());
      assertEquals(graph.tag(), survivingClone.refs().get("refs/tags/source-v1").targetObjectId());
    }

    restart(database);
    assertRepositoryRowsPresent(database, sourceName);
    assertRepositoryRowsPresent(database, disposableTarget);
    assertRepositoryRowsPresent(database, survivingTarget);

    ObjectId sourceTip;
    try (HibernateSessionFactoryProvider provider = provider(database)) {
      DefaultHibernateRepositoryFactory repositories =
          new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
      assertGraph(repositories, sourceName, graph.merge(), graph.tag(), "merge-v1");
      assertGraph(repositories, disposableTarget, graph.merge(), graph.tag(), "merge-v1");
      assertGraph(repositories, survivingTarget, graph.merge(), graph.tag(), "merge-v1");

      RepositoryDeletionResult deleted = repositories.deleteRepository(disposableTarget);
      assertTrue(deleted.deletedAnything());
      assertGraph(repositories, sourceName, graph.merge(), graph.tag(), "merge-v1");
      assertGraph(repositories, survivingTarget, graph.merge(), graph.tag(), "merge-v1");

      try (HibernateGitStorage source = repositories.open(sourceName)) {
        sourceTip =
            createCommit(
                source.repository(),
                "source-v2",
                "source-v2.txt",
                "source-v2",
                List.of(graph.merge()));
        updateRef(source.repository(), "refs/heads/main", graph.merge(), sourceTip);
      }

      RepositoryTransferResult fetched =
          repositories.transfer(
              RepositoryTransferRequest.incrementalFetch(
                  sourceName,
                  survivingTarget,
                  List.of(
                      new RefTransferSpec(
                          "refs/heads/main", "refs/heads/draft", graph.merge())),
                  TargetRefPolicy.COMPARE_AND_SET));
      assertEquals(sourceTip, fetched.refs().get("refs/heads/draft").targetObjectId());
      assertTrue(fetched.objectsTransferred() > 0);
    }

    assertRepositoryRowsAbsent(database, disposableTarget);
    assertRepositoryRowsPresent(database, sourceName);
    assertRepositoryRowsPresent(database, survivingTarget);
    restart(database);

    try (HibernateSessionFactoryProvider provider = provider(database)) {
      DefaultHibernateRepositoryFactory repositories =
          new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
      assertGraph(repositories, sourceName, sourceTip, graph.tag(), "source-v2");
      assertGraph(repositories, survivingTarget, sourceTip, graph.tag(), "source-v2");

      RepositoryTransferResult retry =
          repositories.transfer(
              RepositoryTransferRequest.incrementalFetch(
                  sourceName,
                  survivingTarget,
                  List.of(
                      new RefTransferSpec(
                          "refs/heads/main", "refs/heads/draft", graph.merge())),
                  TargetRefPolicy.COMPARE_AND_SET));
      assertTrue(retry.noOp());
      assertEquals(0, retry.objectsTransferred());
      assertEquals(0, retry.bytesTransferred());

      RepositoryDeletionResult deletedSource = repositories.deleteRepository(sourceName);
      assertTrue(deletedSource.deletedAnything());
      assertGraph(repositories, survivingTarget, sourceTip, graph.tag(), "source-v2");
    }

    assertRepositoryRowsAbsent(database, sourceName);
    assertRepositoryRowsAbsent(database, disposableTarget);
    assertRepositoryRowsPresent(database, survivingTarget);
    restart(database);

    try (HibernateSessionFactoryProvider provider = provider(database)) {
      DefaultHibernateRepositoryFactory repositories =
          new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
      assertGraph(repositories, survivingTarget, sourceTip, graph.tag(), "source-v2");
      RepositoryDeletionResult deletedTarget = repositories.deleteRepository(survivingTarget);
      assertTrue(deletedTarget.deletedAnything());
    }

    assertRepositoryRowsAbsent(database, sourceName);
    assertRepositoryRowsAbsent(database, disposableTarget);
    assertRepositoryRowsAbsent(database, survivingTarget);
  }

  private static RepositoryTransferRequest initialClone(
      RepositoryName source, RepositoryName target) {
    return RepositoryTransferRequest.initialClone(
        source,
        target,
        List.of(
            new RefTransferSpec("refs/heads/main", "refs/heads/draft"),
            new RefTransferSpec("refs/tags/v1", "refs/tags/source-v1")));
  }

  private static SourceGraph createSourceGraph(Repository source) throws Exception {
    ObjectId root = createCommit(source, "root", "root.txt", "root", List.of());
    ObjectId main = createCommit(source, "main", "main.txt", "main", List.of(root));
    ObjectId side = createCommit(source, "side", "side.txt", "side", List.of(root));
    ObjectId merge =
        createCommit(source, "merge-v1", "merge.txt", "merge-v1", List.of(main, side));
    updateRef(source, "refs/heads/main", ObjectId.zeroId(), merge);

    ObjectId tag;
    try (ObjectInserter inserter = source.newObjectInserter()) {
      TagBuilder builder = new TagBuilder();
      builder.setObjectId(merge, Constants.OBJ_COMMIT);
      builder.setTag("v1");
      builder.setTagger(new PersonIdent("Transfer Matrix", "transfer@example.invalid"));
      builder.setMessage("database transfer v1");
      tag = inserter.insert(builder);
      inserter.flush();
    }
    updateRef(source, "refs/tags/v1", ObjectId.zeroId(), tag);
    return new SourceGraph(root, main, side, merge, tag);
  }

  private static ObjectId createCommit(
      Repository repository,
      String message,
      String path,
      String content,
      List<ObjectId> parents)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      commit.setParentIds(parents);
      PersonIdent actor = new PersonIdent("Transfer Matrix", "transfer@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static void updateRef(
      Repository repository, String refName, ObjectId expectedOldId, ObjectId newId)
      throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setExpectedOldObjectId(expectedOldId);
    update.setNewObjectId(newId);
    update.disableRefLog();
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD,
        () -> "unexpected ref update result " + result + " for " + refName);
  }

  private static void assertGraph(
      DefaultHibernateRepositoryFactory repositories,
      RepositoryName repositoryName,
      ObjectId expectedHead,
      ObjectId expectedTag,
      String expectedHeadMessage)
      throws Exception {
    try (HibernateGitStorage storage = repositories.open(repositoryName)) {
      Repository repository = storage.repository();
      assertEquals(expectedHead, repository.exactRef("refs/heads/draft").getObjectId());
      assertEquals(expectedTag, repository.exactRef("refs/tags/source-v1").getObjectId());
      try (RevWalk walk = new RevWalk(repository)) {
        RevCommit head = walk.parseCommit(expectedHead);
        assertEquals(expectedHeadMessage, head.getFullMessage());
        RevObject tagObject = walk.parseAny(expectedTag);
        assertTrue(tagObject instanceof RevTag);
        RevTag tag = (RevTag) tagObject;
        walk.parseHeaders(tag);
        assertEquals(tag.getObject().getId(), tag.getObject().getId());
      }
      try (RevWalk walk = new RevWalk(repository)) {
        RevCommit head = walk.parseCommit(expectedHead);
        String path = "source-v2".equals(expectedHeadMessage) ? "source-v2.txt" : "merge.txt";
        try (TreeWalk tree = TreeWalk.forPath(repository, path, head.getTree())) {
          assertNotNull(tree);
          ObjectLoader loader = repository.open(tree.getObjectId(0));
          assertEquals(
              expectedHeadMessage,
              new String(loader.getBytes(), StandardCharsets.UTF_8));
        }
      }
    }
  }

  private static void migrate(DatabaseFixture database) {
    Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(database.migrationLocation())
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
        .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
        .load()
        .migrate();
  }

  private static HibernateSessionFactoryProvider provider(DatabaseFixture database) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", database.url());
    properties.put("hibernate.connection.username", database.username());
    properties.put("hibernate.connection.password", database.password());
    properties.put("hibernate.connection.driver_class", database.driverClass());
    properties.put("hibernate.dialect", database.hibernateDialect());
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static void restart(DatabaseFixture database) throws Exception {
    database.restartBoundary().run();
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
  }

  private static void assertRepositoryRowsPresent(
      DatabaseFixture database, RepositoryName repositoryName) throws Exception {
    assertEquals(1, count(database, "git_repository_lifecycle", repositoryName));
    assertTrue(count(database, "git_packs", repositoryName) > 0);
  }

  private static void assertRepositoryRowsAbsent(
      DatabaseFixture database, RepositoryName repositoryName) throws Exception {
    assertEquals(0, count(database, "git_repository_lifecycle", repositoryName));
    assertEquals(0, count(database, "git_repository_lock", repositoryName));
    assertEquals(0, count(database, "git_packs", repositoryName));
    assertEquals(0, count(database, "git_reflog", repositoryName));
  }

  private static long count(
      DatabaseFixture database, String table, RepositoryName repositoryName) throws Exception {
    String sql = "select count(*) from " + table + " where repository_name = ?";
    try (Connection connection =
            DriverManager.getConnection(
                database.url(), database.username(), database.password());
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, repositoryName.value());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getLong(1);
      }
    }
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }

  record DatabaseFixture(
      String name,
      String url,
      String username,
      String password,
      String driverClass,
      String hibernateDialect,
      String migrationLocation,
      CheckedRunnable restartBoundary) {}

  private record SourceGraph(
      ObjectId root, ObjectId main, ObjectId side, ObjectId merge, ObjectId tag) {}
}
