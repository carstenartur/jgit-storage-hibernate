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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepositoryBuilder;
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
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HibernateRepositoryCoreH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private String repositoryName;

  @BeforeEach
  void setUp() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    repositoryName = "core-h2-" + TEST_COUNTER.incrementAndGet();
    provider = new HibernateSessionFactoryProvider(h2Properties(repositoryName));
    repository =
        new HibernateRepositoryBuilder()
            .setSessionFactory(provider.getSessionFactory())
            .setRepositoryName(repositoryName)
            .build();
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
  void createsRepositoryWithObjectAndRefDatabases() {
    assertNotNull(repository.getObjectDatabase());
    assertNotNull(repository.getRefDatabase());
    assertEquals(repositoryName, repository.getRepositoryName());
    assertTrue(repository.getRefDatabase().performsAtomicTransactions());
  }

  @Test
  void writesAndReadsBlob() throws Exception {
    byte[] content = "Hello, Hibernate-backed JGit".getBytes(StandardCharsets.UTF_8);
    ObjectId blobId;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      blobId = inserter.insert(Constants.OBJ_BLOB, content);
      inserter.flush();
    }

    assertTrue(repository.getObjectDatabase().has(blobId));
    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader loader = reader.open(blobId);
      assertEquals(Constants.OBJ_BLOB, loader.getType());
      assertEquals("Hello, Hibernate-backed JGit", new String(loader.getBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void writesTreeCommitAndRef() throws Exception {
    ObjectId commitId = createCommitWithFile("Initial commit", "README.md", "content");
    updateRef("refs/heads/main", commitId);

    Ref ref = repository.exactRef("refs/heads/main");
    assertNotNull(ref);
    assertEquals(commitId, ref.getObjectId());

    try (RevWalk revWalk = new RevWalk(repository)) {
      RevCommit commit = revWalk.parseCommit(commitId);
      assertEquals("Initial commit", commit.getFullMessage());
    }
  }

  @Test
  void reopensRepositoryAndReadsStoredCommit() throws Exception {
    ObjectId commitId = createCommitWithFile("Persisted commit", "file.txt", "persisted");
    updateRef("refs/heads/main", commitId);
    repository.close();

    repository =
        new HibernateRepositoryBuilder()
            .setSessionFactory(provider.getSessionFactory())
            .setRepositoryName(repositoryName)
            .build();

    Ref ref = repository.exactRef("refs/heads/main");
    assertNotNull(ref);
    assertEquals(commitId, ref.getObjectId());
    try (RevWalk revWalk = new RevWalk(repository)) {
      assertEquals("Persisted commit", revWalk.parseCommit(commitId).getFullMessage());
    }
  }

  @Test
  void supportsMultiplePacks() throws Exception {
    ObjectId first;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      first = inserter.insert(Constants.OBJ_BLOB, "first".getBytes(StandardCharsets.UTF_8));
      inserter.flush();
    }
    ObjectId second;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      second = inserter.insert(Constants.OBJ_BLOB, "second".getBytes(StandardCharsets.UTF_8));
      inserter.flush();
    }

    assertTrue(repository.getObjectDatabase().has(first));
    assertTrue(repository.getObjectDatabase().has(second));
  }

  @Test
  void writesAndReadsQueryableReflog() throws Exception {
    ObjectId commitId = createCommitWithFile("Reflog commit", "file.txt", "content");
    HibernateReflogWriter writer = repository.getReflogWriter();
    PersonIdent who = new PersonIdent("Test User", "test@example.com");
    writer.log("refs/heads/main", ObjectId.zeroId(), commitId, who, "commit: reflog test");

    ReflogReader reader = repository.getReflogReader("refs/heads/main");
    ReflogEntry entry = reader.getLastEntry();
    assertNotNull(entry);
    assertEquals(commitId, entry.getNewId());
    assertEquals("commit: reflog test", entry.getComment());
    assertEquals("Test User", entry.getWho().getName());
  }

  @Test
  void returnsEmptyReflogForUnknownRef() throws Exception {
    ReflogReader reader = repository.getReflogReader("refs/heads/does-not-exist");
    assertNull(reader.getLastEntry());
    assertTrue(reader.getReverseEntries().isEmpty());
  }

  @Test
  void factoryOpensAndCreatesRepository() {
    try (HibernateGitStorage storage =
        new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
            .open(new RepositoryName(repositoryName + "-factory"))) {
      assertNotNull(storage.repository());
    }
  }

  private ObjectId createCommitWithFile(String message, String path, String content) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, blobId);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(new PersonIdent("Test User", "test@example.com"));
      commit.setCommitter(new PersonIdent("Test User", "test@example.com"));
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private void updateRef(String refName, ObjectId objectId) throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setNewObjectId(objectId);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD,
        () -> "unexpected ref update result " + result);
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
