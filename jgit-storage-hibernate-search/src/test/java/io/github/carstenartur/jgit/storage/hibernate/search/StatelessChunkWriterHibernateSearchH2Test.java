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
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class StatelessChunkWriterHibernateSearchH2Test {

  private static final String CHUNK_WRITER_PROPERTY =
      "jgit.storage.hibernate.pack.chunk_writer";

  @Test
  void statelessRawChunkPersistenceAndStatefulSearchIndexingCoexist() throws Exception {
    String repositoryName = "search-stateless-" + UUID.randomUUID();
    Properties properties = h2Properties(repositoryName);
    properties.put(CHUNK_WRITER_PROPERTY, "stateless");

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ObjectId commitId = createLargeCommit(repository);

      assertTrue(chunkCount(provider) > 0L, "The large pack must use raw chunk rows");
      GitCommitIndex projection =
          new CommitIndexer(provider.getSessionFactory(), repositoryName)
              .indexCommit(repository, commitId);
      assertEquals(commitId.name(), projection.getObjectId());

      GitHistorySearchService search =
          new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(
          List.of(commitId.name()),
          objectIds(search.searchCommitText(repositoryName, "stateless searchable", 10)));
      assertEquals(
          List.of(commitId.name()),
          objectIds(search.searchCommitText(repositoryName, "large-payload.bin", 10)));
    }
  }

  private static ObjectId createLargeCommit(HibernateRepository repository) throws Exception {
    byte[] payload = new byte[2 * 1024 * 1024 + 257];
    new Random(0x5345415243484cL).nextBytes(payload);
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, payload);
      TreeFormatter tree = new TreeFormatter();
      tree.append("large-payload.bin", FileMode.REGULAR_FILE, blobId);
      ObjectId treeId = inserter.insert(tree);

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(new PersonIdent("Search Writer", "search@example.com"));
      commit.setCommitter(new PersonIdent("Search Writer", "search@example.com"));
      commit.setMessage("Stateless searchable pack publication");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static List<String> objectIds(List<GitCommitIndex> hits) {
    return hits.stream().map(GitCommitIndex::getObjectId).toList();
  }

  private static long chunkCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(c) FROM GitPackChunkEntity c", Long.class)
          .getSingleResult();
    }
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
