/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.TreeFormatter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MultiInstancePostgreSqlIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_multi_instance")
          .withUsername("postgres")
          .withPassword("postgres");

  private String schema;
  private String schemaUrl;

  @BeforeEach
  void setUp() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    schema = "multi_instance_" + TEST_COUNTER.incrementAndGet();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }
    schemaUrl = appendParameter(POSTGRESQL.getJdbcUrl(), "currentSchema", schema);
    Flyway.configure()
        .dataSource(schemaUrl, POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
        .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .load()
        .migrate();
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  void exactlyOneExpectedOldIdUpdateWinsAcrossIndependentSessionFactories() throws Exception {
    String repositoryName = "shared-repository";
    try (HibernateSessionFactoryProvider providerA = provider();
        HibernateRepository repositoryA =
            HibernateRepository.create(providerA.getSessionFactory(), repositoryName)) {
      repositoryA.create(true);
      ObjectId initial = createCommit(repositoryA, "initial", null);
      assertEquals(RefUpdate.Result.NEW, update(repositoryA, initial, ObjectId.zeroId()));

      try (HibernateSessionFactoryProvider providerB = provider();
          HibernateRepository repositoryB =
              HibernateRepository.create(providerB.getSessionFactory(), repositoryName)) {
        ObjectId candidateA = createCommit(repositoryA, "candidate-a", initial);
        ObjectId candidateB = createCommit(repositoryB, "candidate-b", initial);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
          Future<RefUpdate.Result> resultA =
              executor.submit(updateTask(repositoryA, initial, candidateA, ready, start));
          Future<RefUpdate.Result> resultB =
              executor.submit(updateTask(repositoryB, initial, candidateB, ready, start));

          assertTrue(ready.await(10, TimeUnit.SECONDS));
          start.countDown();

          List<RefUpdate.Result> results = List.of(resultA.get(), resultB.get());
          assertEquals(1, results.stream().filter(MultiInstancePostgreSqlIntegrationTest::success).count());
          assertEquals(
              1,
              results.stream().filter(result -> result == RefUpdate.Result.LOCK_FAILURE).count());
        }
      }
    }

    try (HibernateSessionFactoryProvider verifierProvider = provider();
        HibernateRepository verifier =
            HibernateRepository.create(verifierProvider.getSessionFactory(), repositoryName)) {
      ObjectId winner = verifier.resolve(Constants.R_HEADS + "main");
      assertNotNull(winner);
      List<ReflogEntry> entries =
          verifier.getReflogReader(Constants.R_HEADS + "main").getReverseEntries();
      assertEquals(2, entries.size());
      assertEquals(winner, entries.get(0).getNewId());
    }
  }

  private Callable<RefUpdate.Result> updateTask(
      HibernateRepository repository,
      ObjectId expected,
      ObjectId candidate,
      CountDownLatch ready,
      CountDownLatch start) {
    return () -> {
      ready.countDown();
      assertTrue(start.await(10, TimeUnit.SECONDS));
      return update(repository, candidate, expected);
    };
  }

  private static boolean success(RefUpdate.Result result) {
    return result == RefUpdate.Result.FAST_FORWARD || result == RefUpdate.Result.FORCED;
  }

  private static RefUpdate.Result update(
      HibernateRepository repository, ObjectId newId, ObjectId expectedOldId) throws Exception {
    RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
    update.setExpectedOldObjectId(expectedOldId);
    update.setNewObjectId(newId);
    update.setRefLogMessage("multi-instance update", true);
    return update.update();
  }

  private static ObjectId createCommit(
      HibernateRepository repository, String value, ObjectId parent) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, value.getBytes(UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(value + ".txt", FileMode.REGULAR_FILE, blob);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent ident = new PersonIdent("Multi Instance", "multi@example.invalid");
      commit.setAuthor(ident);
      commit.setCommitter(ident);
      commit.setMessage(value);
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", schemaUrl);
    properties.put("hibernate.connection.username", POSTGRESQL.getUsername());
    properties.put("hibernate.connection.password", POSTGRESQL.getPassword());
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static String appendParameter(String url, String key, String value) {
    return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
  }
}
