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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.nio.charset.StandardCharsets;
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
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHistorySearchH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private String repositoryName;

  @BeforeEach
  void setUp() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    repositoryName = "history-h2-" + TEST_COUNTER.incrementAndGet();
    provider = new HibernateSessionFactoryProvider(h2Properties(repositoryName), SearchEntities.annotatedClasses());
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
  void indexesCommitMessagesPathsAndText() throws Exception {
    ObjectId commitId = createCommitWithFile("Add workflow DSL", "workflows/demo.apflow", "node gain");
    CommitIndexer indexer = new CommitIndexer(provider.getSessionFactory(), repositoryName);
    GitCommitIndex projection = indexer.indexCommit(repository, commitId);

    assertEquals(commitId.name(), projection.getObjectId());
    assertTrue(projection.getChangedPaths().contains("workflows/demo.apflow"));
    assertTrue(projection.getChangedText().contains("node gain"));

    GitHistorySearchService searchService = new GitHistorySearchService(provider.getSessionFactory());
    List<GitCommitIndex> hits = searchService.searchCommitText(repositoryName, "workflow", 10);
    assertEquals(1, hits.size());
  }

  private ObjectId createCommitWithFile(String message, String path, String content) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, blobId);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(new PersonIdent("Indexer", "indexer@example.com"));
      commit.setCommitter(new PersonIdent("Indexer", "indexer@example.com"));
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
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
