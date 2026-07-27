/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommitTimestampSemanticsH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private String repositoryName;

  @BeforeEach
  void setUp() throws Exception {
    repositoryName = "timestamp-semantics-" + TEST_COUNTER.incrementAndGet();
    provider =
        new HibernateSessionFactoryProvider(
            h2Properties(repositoryName), SearchEntities.annotatedClasses());
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
  }

  @AfterEach
  void tearDown() {
    if (repository != null) {
      repository.close();
    }
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void indexesAndQueriesAuthorAndCommitterTimesIndependently() throws Exception {
    Instant firstAuthorTime = Instant.parse("2026-01-10T10:00:00Z");
    Instant firstCommitterTime = Instant.parse("2026-03-10T10:00:00Z");
    Instant secondAuthorTime = Instant.parse("2026-02-10T10:00:00Z");
    Instant secondCommitterTime = Instant.parse("2026-01-20T10:00:00Z");

    ObjectId first = createCommit("first", firstAuthorTime, firstCommitterTime);
    ObjectId second = createCommit("second", secondAuthorTime, secondCommitterTime);

    CommitIndexer indexer = new CommitIndexer(provider.getSessionFactory(), repositoryName);
    GitCommitIndex firstProjection = indexer.indexCommit(repository, first);
    GitCommitIndex secondProjection = indexer.indexCommit(repository, second);

    assertEquals(firstAuthorTime, firstProjection.getAuthorTime());
    assertEquals(firstCommitterTime, firstProjection.getCommitterTime());
    assertEquals("Author", firstProjection.getAuthorName());
    assertEquals("Committer", firstProjection.getCommitterName());
    assertEquals(secondAuthorTime, secondProjection.getAuthorTime());
    assertEquals(secondCommitterTime, secondProjection.getCommitterTime());

    GitHistorySearchService service = new GitHistorySearchService(provider.getSessionFactory());
    Instant januaryStart = Instant.parse("2026-01-01T00:00:00Z");
    Instant januaryEnd = Instant.parse("2026-01-31T23:59:59Z");

    assertEquals(
        List.of(second.name()),
        objectIds(service.findBetween(repositoryName, januaryStart, januaryEnd, 10)));
    assertEquals(
        List.of(first.name()),
        objectIds(service.findAuthoredBetween(repositoryName, januaryStart, januaryEnd, 10)));
    assertEquals(
        List.of(first.name()),
        objectIds(
            service.findChanges(
                CommitHistoryQuery.forRepository(repositoryName)
                    .usingAuthorTime()
                    .between(januaryStart, januaryEnd)
                    .build())));
  }

  private ObjectId createCommit(String value, Instant authorTime, Instant committerTime)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, value.getBytes(UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(value + ".txt", FileMode.REGULAR_FILE, blob);
      ObjectId treeId = inserter.insert(tree);

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(
          new PersonIdent(
              "Author", "author@example.invalid", Date.from(authorTime), UTC));
      commit.setCommitter(
          new PersonIdent(
              "Committer", "committer@example.invalid", Date.from(committerTime), UTC));
      commit.setMessage(value);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static List<String> objectIds(List<GitCommitIndex> projections) {
    return projections.stream().map(GitCommitIndex::getObjectId).toList();
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
