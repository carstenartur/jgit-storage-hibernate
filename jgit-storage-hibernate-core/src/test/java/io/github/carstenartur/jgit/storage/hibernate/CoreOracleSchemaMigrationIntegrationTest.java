/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.assertBootstrapPreserved;
import static io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.bootstrapSnapshot;
import static io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.reflogSnapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.ReflogSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogBatchProcessor;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendCommand;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogBatchRejectedException;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/** Flyway-backed Oracle tests: no Hibernate schema creation is permitted in this class. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 420, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CoreOracleSchemaMigrationIntegrationTest {
  @Container static final OracleContainer ORACLE = OracleIntegrationSupport.container();
  private static final Instant WHEN = Instant.parse("2026-11-04T12:34:56.123456789Z");
  private static final String ACTOR = "作者 🦊";

  @BeforeAll
  static void migrateAndVerifySchema() throws Exception {
    try (Connection connection = OracleIntegrationSupport.connection(ORACLE);
        var statement = connection.createStatement()) {
      statement.execute("create table unrelated_application_data (id number(10) primary key)");
      statement.execute("insert into unrelated_application_data values (7)");
    }
    Flyway flyway = OracleIntegrationSupport.flyway(ORACLE);
    assertEquals(1, flyway.migrate().migrationsExecuted);
    assertEquals("0.12.0", flyway.info().current().getVersion().getVersion());
    assertEquals(0, flyway.migrate().migrationsExecuted);
    flyway.validate();
    try (Connection connection = OracleIntegrationSupport.connection(ORACLE)) {
      try (var statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "select parameter, value from nls_database_parameters "
                      + "where parameter in ('NLS_CHARACTERSET', 'NLS_NCHAR_CHARACTERSET')")) {
        Map<String, String> charsets = new java.util.HashMap<>();
        while (rows.next()) charsets.put(rows.getString(1), rows.getString(2));
        assertEquals("AL32UTF8", charsets.get("NLS_CHARACTERSET"));
        assertEquals("AL16UTF16", charsets.get("NLS_NCHAR_CHARACTERSET"));
      }
      Map<String, Set<String>> columns =
          Map.of(
              "git_repository_lifecycle", Set.of("repository_name", "created_at"),
              "git_repository_lock", Set.of("repository_name", "created_at"),
              "git_packs",
                  Set.of(
                      "id",
                      "repository_name",
                      "pack_name",
                      "pack_extension",
                      "data",
                      "file_size",
                      "committed",
                      "write_token",
                      "write_lease_until",
                      "created_at",
                      "committed_at",
                      "pack_source",
                      "last_modified",
                      "object_count",
                      "delta_count",
                      "index_version",
                      "min_update_index",
                      "max_update_index"),
              "git_pack_chunks", Set.of("id", "pack_id", "chunk_index", "chunk_data", "chunk_size"),
              "git_reflog",
                  Set.of(
                      "id",
                      "version",
                      "repository_name",
                      "delivery_id",
                      "ref_name",
                      "ref_name_key",
                      "old_id",
                      "new_id",
                      "who_name",
                      "who_email",
                      "who_when",
                      "message"));
      for (var table : columns.entrySet()) {
        Set<String> actual = new HashSet<>();
        try (ResultSet rows =
            connection
                .getMetaData()
                .getColumns(
                    null, connection.getSchema(), table.getKey().toUpperCase(Locale.ROOT), "%")) {
          while (rows.next()) actual.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        assertEquals(table.getValue(), actual, table.getKey());
      }
      assertIndexes(
          connection,
          "git_packs",
          Set.of("idx_pack_repo_committed", "idx_pack_repo_lease", "uk_pack_repo_name_ext"));
      assertIndexes(connection, "git_pack_chunks", Set.of("uk_pack_chunk_index"));
      assertIndexes(
          connection,
          "git_reflog",
          Set.of("idx_reflog_repo_ref_key_id", "idx_reflog_repo_delivery"));
      assertCascade(connection, "git_packs", "fk_pack_repository_lifecycle");
      assertCascade(connection, "git_repository_lock", "fk_repository_lock_lifecycle");
      assertCascade(connection, "git_pack_chunks", "fk_pack_chunk_pack");
      try (var statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("select id from unrelated_application_data")) {
        assertTrue(rows.next());
        assertEquals(7, rows.getInt(1));
      }
    }
    try (HibernateSessionFactoryProvider ignored = provider()) {
      assertNotNull(ignored.getSessionFactory());
    }
  }

  @Test
  void reopensUnicodeGitObjectsInlineAndChunkedBlobsAndCascadesOnlyDeletedRepository()
      throws Exception {
    RepositoryName name = new RepositoryName("資料庫-🦊-payload");
    byte[] inline = randomBytes(48 * 1024);
    byte[] large = randomBytes(2 * 1024 * 1024 + 337);
    ObjectId blob;
    ObjectId empty;
    ObjectId commit;
    long inlineId;
    long emptyInlineId;
    List<Long> chunkedIds;
    try (HibernateSessionFactoryProvider provider = provider()) {
      var factory = new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
      try (HibernateGitStorage storage = factory.open(name)) {
        try (ObjectInserter inserter = storage.repository().newObjectInserter()) {
          blob = inserter.insert(Constants.OBJ_BLOB, large);
          empty = inserter.insert(Constants.OBJ_BLOB, new byte[0]);
          inserter.flush();
        }
        commit = commit(storage.repository(), "Unicode 提交 🦊", blob, null);
        assertEquals(RefUpdate.Result.NEW, update(storage.repository(), commit, ObjectId.zeroId()));
      }
      try (Session session = provider.getSessionFactory().openSession()) {
        var transaction = session.beginTransaction();
        session.doWork(
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("alter session set time_zone = '+09:00'");
                try (ResultSet rows = statement.executeQuery("select sessiontimezone from dual")) {
                  assertTrue(rows.next());
                  assertEquals("+09:00", rows.getString(1));
                }
              }
            });
        GitPackEntity legacy = new GitPackEntity();
        legacy.setRepositoryName(name.value());
        legacy.setPackName("legacy-inline-🦊");
        legacy.setPackExtension("idx");
        legacy.setData(inline);
        legacy.setFileSize(inline.length);
        legacy.setCommitted(true);
        legacy.setCreatedAt(WHEN);
        legacy.setCommittedAt(WHEN);
        session.persist(legacy);
        GitPackEntity emptyInline = new GitPackEntity();
        emptyInline.setRepositoryName(name.value());
        emptyInline.setPackName("legacy-empty");
        emptyInline.setPackExtension("idx");
        emptyInline.setData(new byte[0]);
        emptyInline.setFileSize(0);
        emptyInline.setCommitted(true);
        emptyInline.setCreatedAt(WHEN);
        session.persist(emptyInline);
        transaction.commit();
        emptyInlineId = emptyInline.getId();
        inlineId = legacy.getId();
        assertTrue(inlineId > 0);
        chunkedIds =
            session
                .createQuery(
                    "select p.id from GitPackEntity p where p.repositoryName = :repo and p.data is"
                        + " null and p.fileSize > 1048576",
                    Long.class)
                .setParameter("repo", name.value())
                .getResultList();
        assertFalse(chunkedIds.isEmpty());
        assertTrue(
            session
                    .createQuery(
                        "select min(c.id) from GitPackChunkEntity c where c.packId in :ids",
                        Long.class)
                    .setParameterList("ids", chunkedIds)
                    .getSingleResult()
                > 0);
        assertTrue(
            session
                    .createQuery(
                        "select count(c) from GitPackChunkEntity c where c.packId in :ids",
                        Long.class)
                    .setParameterList("ids", chunkedIds)
                    .getSingleResult()
                > 1);
      }
      try (HibernateGitStorage survivor = factory.open(new RepositoryName("payload-survivor"))) {
        assertEquals(
            RefUpdate.Result.NEW,
            update(
                survivor.repository(),
                commit(survivor.repository(), "survivor", null, null),
                ObjectId.zeroId()));
      }
    }
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    try (HibernateSessionFactoryProvider reopened = provider()) {
      var factory = new DefaultHibernateRepositoryFactory(reopened.getSessionFactory());
      try (HibernateGitStorage storage = factory.open(name);
          RevWalk walk = new RevWalk(storage.repository())) {
        assertEquals(commit, storage.repository().exactRef("refs/heads/main").getObjectId());
        assertEquals("Unicode 提交 🦊", walk.parseCommit(commit).getFullMessage());
        assertArrayEquals(large, storage.repository().open(blob).getBytes());
        assertArrayEquals(new byte[0], storage.repository().open(empty).getBytes());
        assertTrue(
            storage
                .repository()
                .getReflogReader("refs/heads/main")
                .getLastEntry()
                .getComment()
                .contains("更新 🦊"));
      }
      try (Session session = reopened.getSessionFactory().openSession()) {
        GitPackEntity persisted = session.find(GitPackEntity.class, inlineId);
        assertArrayEquals(inline, persisted.getData());
        assertEquals(WHEN, persisted.getCreatedAt());
        assertTrue(persisted.isCommitted());
      }
      // Oracle may return SQL NULL for an empty binary bind. The storage contract is a
      // zero-length readable extension, including after a new SessionFactory, on both read paths.
      io.github.carstenartur.jgit.storage.hibernate.objects.OraclePackStorageContract
          .verifyEmptyExtensionRead(reopened, name.value(), emptyInlineId);
      assertTrue(factory.deleteRepository(name).deletedAnything());
      try (Session session = reopened.getSessionFactory().openSession()) {
        assertEquals(
            0L,
            session
                .createQuery(
                    "select count(c) from GitPackChunkEntity c where c.packId in :ids", Long.class)
                .setParameterList("ids", chunkedIds)
                .getSingleResult());
        assertEquals(
            0L,
            session
                .createQuery(
                    "select count(r) from GitReflogEntity r where r.repositoryName = :repo",
                    Long.class)
                .setParameter("repo", name.value())
                .getSingleResult());
      }
      try (HibernateGitStorage survivor = factory.open(new RepositoryName("payload-survivor"))) {
        assertNotNull(survivor.repository().exactRef("refs/heads/main"));
      }
    }
  }

  @Test
  void durableUnicodeBatchReplaysEmptyAndNullMessagesAfterProviderReopen() throws Exception {
    String name = "reflog-資料庫-🦊";
    String prefix = "refs/heads/" + "長".repeat(128);
    String boundary = "🦊".repeat(999) + "漢字"; // Exactly 2000 UTF-16 code units.
    List<ReflogSnapshot> bootstrap;
    List<ReflogAppendCommand> commands =
        List.of(
            command("unicode", prefix + "甲", ObjectId.zeroId(), id(1), boundary),
            command("empty", prefix + "甲", id(1), id(2), ""),
            command("null", prefix + "乙", ObjectId.zeroId(), id(3), null));
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), name)) {
      repository.create(true);
      bootstrap = bootstrapSnapshot(provider, name);
      assertEquals(
          List.of(
              ReflogAppendResult.Status.APPENDED,
              ReflogAppendResult.Status.APPENDED,
              ReflogAppendResult.Status.APPENDED),
          new HibernateReflogBatchProcessor(provider.getSessionFactory())
              .execute(name, commands).stream().map(ReflogAppendResult::status).toList());
    }
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(provider.getSessionFactory());
      assertTrue(
          processor.execute(name, commands).stream()
              .allMatch(result -> result.status() == ReflogAppendResult.Status.ALREADY_APPLIED));
      ReflogBatchRejectedException failure =
          assertThrows(
              ReflogBatchRejectedException.class,
              () ->
                  processor.execute(
                      name, List.of(command("empty", prefix + "甲", id(1), id(2), "changed"))));
      assertEquals(
          ReflogBatchRejectedException.Reason.DELIVERY_ID_REUSED_WITH_DIFFERENT_PAYLOAD,
          failure.reason());
      try (Session session = provider.getSessionFactory().openSession()) {
        List<GitReflogEntity> rows =
            session
                .createQuery(
                    "from GitReflogEntity r where r.repositoryName = :repo "
                        + "and r.deliveryId in :deliveryIds order by r.id",
                    GitReflogEntity.class)
                .setParameter("repo", name)
                .setParameterList(
                    "deliveryIds", commands.stream().map(ReflogAppendCommand::deliveryId).toList())
                .getResultList();
        assertEquals(3, rows.size());
        assertEquals(boundary, rows.getFirst().getMessage());
        assertEquals(ACTOR, rows.getFirst().getWhoName());
        assertEquals(commands.getFirst().when(), rows.getFirst().getWhen());
        assertNull(rows.get(1).getMessage());
        assertNull(rows.get(2).getMessage());
        assertTrue(rows.getFirst().getId() > 0);
        assertTrue(rows.get(2).getId() > rows.getFirst().getId());
      }
      assertBootstrapPreserved(provider, name, bootstrap, commands.size());
    }
  }

  @Test
  void failedJdbcBatchRollsBackAndCanRetryTheSameDeliveryIds() throws Exception {
    String name = "oracle-batch-rollback";
    List<ReflogSnapshot> bootstrap;
    List<ReflogAppendCommand> commands =
        List.of(
            command("rollback-first", "refs/heads/main", ObjectId.zeroId(), id(1), "first"),
            command("rollback-second", "refs/heads/main", id(1), id(2), "explode"));
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), name)) {
      repository.create(true);
      bootstrap = bootstrapSnapshot(provider, name);
      sql(
          "alter table git_reflog add constraint reject_oracle_explode check (message <>"
              + " 'explode')");
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(provider.getSessionFactory());
      try {
        Exception failure = assertThrows(Exception.class, () -> processor.execute(name, commands));
        assertEquals(
            HibernateReflogBatchProcessor.RetryAdvice.RETRY_WITH_SAME_DELIVERY_IDS,
            HibernateReflogBatchProcessor.retryAdvice(failure));
        // Assert the complete pre-batch database state, including bootstrap HEAD content.
        // This proves both attempted delivery rows rolled back without erasing existing history.
        assertEquals(bootstrap, reflogSnapshot(provider, name));
      } finally {
        sql("alter table git_reflog drop constraint reject_oracle_explode");
      }
      assertTrue(
          processor.execute(name, commands).stream()
              .allMatch(result -> result.status() == ReflogAppendResult.Status.APPENDED));
      assertBootstrapPreserved(provider, name, bootstrap, commands.size());
    }
    try (HibernateSessionFactoryProvider reopened = provider()) {
      assertTrue(
          new HibernateReflogBatchProcessor(reopened.getSessionFactory())
              .execute(name, commands).stream()
                  .allMatch(
                      result -> result.status() == ReflogAppendResult.Status.ALREADY_APPLIED));
      assertBootstrapPreserved(reopened, name, bootstrap, commands.size());
    }
  }

  @Test
  void exactlyOneCompareAndSetWinsAcrossIndependentSessionFactories() throws Exception {
    String name = "oracle-cas-race";
    ObjectId winner;
    try (HibernateSessionFactoryProvider first = provider();
        HibernateSessionFactoryProvider second = provider();
        HibernateRepository a = HibernateRepository.create(first.getSessionFactory(), name);
        HibernateRepository b = HibernateRepository.create(second.getSessionFactory(), name)) {
      a.create(true);
      ObjectId initial = commit(a, "initial", null, null);
      assertEquals(RefUpdate.Result.NEW, update(a, initial, ObjectId.zeroId()));
      ObjectId candidateA = commit(a, "candidate-a", null, initial);
      ObjectId candidateB = commit(b, "candidate-b", null, initial);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var resultA =
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(15, TimeUnit.SECONDS));
                  return update(a, candidateA, initial);
                });
        var resultB =
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(15, TimeUnit.SECONDS));
                  return update(b, candidateB, initial);
                });
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        start.countDown();
        List<RefUpdate.Result> results =
            List.of(resultA.get(60, TimeUnit.SECONDS), resultB.get(60, TimeUnit.SECONDS));
        assertEquals(
            1,
            results.stream()
                .filter(
                    result ->
                        result == RefUpdate.Result.FAST_FORWARD
                            || result == RefUpdate.Result.FORCED)
                .count());
        assertEquals(
            1, results.stream().filter(result -> result == RefUpdate.Result.LOCK_FAILURE).count());
        winner = results.getFirst() == RefUpdate.Result.LOCK_FAILURE ? candidateB : candidateA;
      }
    }
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    try (HibernateSessionFactoryProvider reopened = provider();
        HibernateRepository repository =
            HibernateRepository.create(reopened.getSessionFactory(), name)) {
      assertEquals(winner, repository.resolve("refs/heads/main"));
      var entries = repository.getReflogReader("refs/heads/main").getReverseEntries();
      assertEquals(2, entries.size());
      assertEquals(winner, entries.getFirst().getNewId());
    }
  }

  @Test
  void unrelatedRepositoryProgressesAndRollbackReleasesHeldRowLock() throws Exception {
    try (HibernateSessionFactoryProvider first = provider();
        HibernateSessionFactoryProvider second = provider();
        HibernateRepository held =
            HibernateRepository.create(first.getSessionFactory(), "oracle-lock-held");
        HibernateRepository independent =
            HibernateRepository.create(second.getSessionFactory(), "oracle-lock-independent")) {
      held.create(true);
      independent.create(true);
      ObjectId heldCommit = commit(held, "held", null, null);
      ObjectId independentCommit = commit(independent, "independent", null, null);
      try (Session session = first.getSessionFactory().openSession();
          var executor = Executors.newFixedThreadPool(2)) {
        var transaction = session.beginTransaction();
        new HibernateTransactionContext(first.getSessionFactory())
            .acquireRepositoryLock(session, "oracle-lock-held");
        CountDownLatch attempted = new CountDownLatch(1);
        var blocked =
            executor.submit(
                () -> {
                  attempted.countDown();
                  return update(held, heldCommit, ObjectId.zeroId());
                });
        try {
          assertTrue(attempted.await(15, TimeUnit.SECONDS));
          var other =
              executor.submit(() -> update(independent, independentCommit, ObjectId.zeroId()));
          assertEquals(RefUpdate.Result.NEW, other.get(30, TimeUnit.SECONDS));
          assertFalse(blocked.isDone(), "the held coordination row must block this repository");
        } finally {
          transaction.rollback();
        }
        assertEquals(RefUpdate.Result.NEW, blocked.get(30, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void preservesDuplicatePublicationErrorsLeasesCleanupAndStagedDeletion() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      io.github.carstenartur.jgit.storage.hibernate.objects.OraclePackStorageContract
          .verifyDuplicateConstraint(provider);
      io.github.carstenartur.jgit.storage.hibernate.objects.OraclePackStorageContract
          .verifyLeaseCleanupAndDeletion(provider);
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    return new HibernateSessionFactoryProvider(OracleIntegrationSupport.properties(ORACLE));
  }

  private static void sql(String sql) throws Exception {
    try (Connection connection = OracleIntegrationSupport.connection(ORACLE);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void assertIndexes(Connection connection, String table, Set<String> expected)
      throws Exception {
    Set<String> actual = new HashSet<>();
    try (ResultSet rows =
        connection
            .getMetaData()
            .getIndexInfo(
                null, connection.getSchema(), table.toUpperCase(Locale.ROOT), false, false)) {
      while (rows.next()) {
        String name = rows.getString("INDEX_NAME");
        if (name != null) actual.add(name.toLowerCase(Locale.ROOT));
      }
    }
    assertTrue(actual.containsAll(expected), () -> table + ": " + actual);
    assertFalse(actual.contains("idx_pack_repo"));
    assertFalse(actual.contains("idx_pack_repo_name"));
    assertFalse(actual.contains("idx_pack_chunk_pack"));
    assertFalse(actual.contains("idx_reflog_repo"));
    assertFalse(actual.contains("idx_reflog_repo_ref"));
  }

  private static void assertCascade(Connection connection, String table, String expected)
      throws Exception {
    try (ResultSet rows =
        connection
            .getMetaData()
            .getImportedKeys(null, connection.getSchema(), table.toUpperCase(Locale.ROOT))) {
      while (rows.next()) {
        if (expected.equalsIgnoreCase(rows.getString("FK_NAME"))) {
          assertEquals(java.sql.DatabaseMetaData.importedKeyCascade, rows.getShort("DELETE_RULE"));
          return;
        }
      }
    }
    fail("Missing foreign key " + expected);
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new Random(7121).nextBytes(bytes);
    return bytes;
  }

  private static ObjectId id(int value) {
    return ObjectId.fromString("%040x".formatted(value));
  }

  private static ReflogAppendCommand command(
      String delivery, String ref, ObjectId oldId, ObjectId newId, String message) {
    return new ReflogAppendCommand(
        delivery, ref, oldId, newId, ACTOR, "作者@example.invalid", WHEN, message);
  }

  private static ObjectId commit(
      Repository repository, String message, ObjectId blob, ObjectId parent) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      if (blob == null)
        blob =
            inserter.insert(
                Constants.OBJ_BLOB, message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("資料.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) commit.setParentId(parent);
      PersonIdent actor = new PersonIdent(ACTOR, "oracle@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private static RefUpdate.Result update(Repository repository, ObjectId candidate, ObjectId oldId)
      throws Exception {
    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(oldId);
    update.setNewObjectId(candidate);
    update.setRefLogIdent(new PersonIdent(ACTOR, "oracle@example.invalid"));
    update.setRefLogMessage("更新 🦊", true);
    return update.update();
  }
}
