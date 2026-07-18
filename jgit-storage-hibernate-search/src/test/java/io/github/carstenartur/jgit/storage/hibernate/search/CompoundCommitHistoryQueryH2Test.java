/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;

/** Executable audit use case for compound author, changed-path and time queries. */
class CompoundCommitHistoryQueryH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String REPOSITORY_NAME = "payment-platform";

  @Test
  void findsOnlyChangesByOneAuthorInOneSubsystemDuringOneTimeRange() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    String databaseName = "compound-history-" + TEST_COUNTER.incrementAndGet();

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(
                h2Properties(databaseName), SearchEntities.annotatedClasses());
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName(REPOSITORY_NAME))) {
      Repository repository = storage.repository();
      CommitIndexer indexer = new CommitIndexer(provider.getSessionFactory(), REPOSITORY_NAME);

      Map<String, String> snapshot = new LinkedHashMap<>();
      snapshot.put("services/payments/fraud/rules.yaml", "threshold: 1000\n");
      ObjectId initial =
          commitSnapshot(
              repository,
              null,
              "Add fraud rules",
              "Alice",
              "alice@example.com",
              Instant.parse("2026-01-10T10:00:00Z"),
              snapshot);
      GitCommitIndex initialProjection = indexer.indexCommit(repository, initial);

      snapshot.put("services/payments/fraud/model.yaml", "model: v2\n");
      ObjectId model =
          commitSnapshot(
              repository,
              initial,
              "Update fraud model",
              "Bob",
              "bob@example.com",
              Instant.parse("2026-02-10T10:00:00Z"),
              snapshot);
      GitCommitIndex modelProjection = indexer.indexCommit(repository, model);

      snapshot.put("services/profile/ui.yaml", "theme: dark\n");
      ObjectId profile =
          commitSnapshot(
              repository,
              model,
              "Add profile theme",
              "Alice",
              "alice@example.com",
              Instant.parse("2026-02-15T10:00:00Z"),
              snapshot);
      indexer.indexCommit(repository, profile);

      snapshot.put("services/payments/fraud/rules.yaml", "threshold: 500\n");
      ObjectId tunedRules =
          commitSnapshot(
              repository,
              profile,
              "Tighten fraud threshold",
              "Alice",
              "alice@example.com",
              Instant.parse("2026-03-10T10:00:00Z"),
              snapshot);
      indexer.indexCommit(repository, tunedRules);

      assertEquals(
          "services/payments/fraud/rules.yaml", initialProjection.getChangedPaths());
      assertEquals(
          "services/payments/fraud/model.yaml", modelProjection.getChangedPaths());
      assertFalse(
          modelProjection.getChangedPaths().contains("rules.yaml"),
          "unchanged files must not be indexed as changed paths");

      GitHistorySearchService history =
          new GitHistorySearchService(provider.getSessionFactory());
      List<GitCommitIndex> aliceFraudChangesInQuarter =
          history.findChanges(
              CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                  .authoredBy("alice@example.com")
                  .touchingPath("services/payments/fraud/")
                  .between(
                      Instant.parse("2026-02-01T00:00:00Z"),
                      Instant.parse("2026-03-31T23:59:59Z"))
                  .limit(20)
                  .build());

      assertEquals(
          List.of(tunedRules.name()),
          aliceFraudChangesInQuarter.stream().map(GitCommitIndex::getObjectId).toList());
      assertEquals(3, history.findByAuthorEmail(REPOSITORY_NAME, "alice@example.com", 20).size());
      assertEquals(3, history.findByPath(REPOSITORY_NAME, "payments/fraud", 20).size());

      assertEquals(
          0,
          history
              .findChanges(
                  CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                      .touchingPath("%")
                      .limit(20)
                      .build())
              .size(),
          "a percent sign in a path fragment must be treated literally");
      assertEquals(
          0,
          history
              .findChanges(
                  CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                      .touchingPath("_")
                      .limit(20)
                      .build())
              .size(),
          "an underscore in a path fragment must be treated literally");
    }
  }

  private static ObjectId commitSnapshot(
      Repository repository,
      ObjectId parent,
      String message,
      String authorName,
      String authorEmail,
      Instant when,
      Map<String, String> files)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      DirCache cache = DirCache.newInCore();
      DirCacheBuilder tree = cache.builder();
      for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
        ObjectId blob =
            inserter.insert(
                Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8));
        DirCacheEntry entry = new DirCacheEntry(file.getKey());
        entry.setFileMode(FileMode.REGULAR_FILE);
        entry.setObjectId(blob);
        tree.add(entry);
      }
      tree.finish();

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(cache.writeTree(inserter));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent actor = new PersonIdent(authorName, authorEmail, when, ZoneOffset.UTC);
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);

      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static Properties h2Properties(String databaseName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
