/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.assertBootstrapPreserved;
import static io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.bootstrapSnapshot;
import static io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.reflogSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.ReflogBatchDatabaseAssertions.ReflogSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.objects.OraclePackStorageContract;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogBatchProcessor;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendCommand;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

/** Executable regressions for fixture assumptions found by real Oracle CI. */
class OracleCoreFixtureH2Test {
  @Test
  void nullPayloadWithZeroFileSizeReadsAsEmptyAfterProviderReopen() throws Exception {
    String repository = "empty-extension";
    Properties properties = properties();
    long packId;
    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties);
        HibernateRepository ignored =
            HibernateRepository.create(provider.getSessionFactory(), repository);
        Session session = provider.getSessionFactory().openSession()) {
      var transaction = session.beginTransaction();
      GitPackEntity empty = new GitPackEntity();
      empty.setRepositoryName(repository);
      empty.setPackName("legacy-empty");
      empty.setPackExtension("idx");
      empty.setCommitted(true);
      empty.setFileSize(0);
      // Reproduce the null value returned by Oracle for the zero-length binary bind.
      empty.setData(null);
      empty.setCreatedAt(Instant.now());
      session.persist(empty);
      transaction.commit();
      packId = empty.getId();
    }
    properties.put("hibernate.hbm2ddl.auto", "validate");
    try (HibernateSessionFactoryProvider reopened =
        new HibernateSessionFactoryProvider(properties)) {
      OraclePackStorageContract.verifyEmptyExtensionRead(reopened, repository, packId);
    }
  }

  @Test
  void failedBatchPreservesBootstrapSnapshotAndRetryAddsOnlyItsDeliveryRows() throws Exception {
    String repository = "bootstrap-batch";
    Properties properties = properties();
    List<ReflogSnapshot> bootstrap;
    List<ReflogAppendCommand> commands =
        List.of(command("first", 0, 1, "first"), command("second", 1, 2, "explode"));
    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties);
        HibernateRepository git =
            HibernateRepository.create(provider.getSessionFactory(), repository)) {
      git.create(true);
      bootstrap = bootstrapSnapshot(provider, repository);
      executeSql(
          provider,
          "alter table git_reflog add constraint reject_fixture_explode check (message <>"
              + " 'explode')");
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(provider.getSessionFactory());
      Exception failure =
          assertThrows(Exception.class, () -> processor.execute(repository, commands));
      assertEquals(
          HibernateReflogBatchProcessor.RetryAdvice.RETRY_WITH_SAME_DELIVERY_IDS,
          HibernateReflogBatchProcessor.retryAdvice(failure));
      assertEquals(bootstrap, reflogSnapshot(provider, repository));
      executeSql(provider, "alter table git_reflog drop constraint reject_fixture_explode");
      assertTrue(
          processor.execute(repository, commands).stream()
              .allMatch(result -> result.status() == ReflogAppendResult.Status.APPENDED));
      assertBootstrapPreserved(provider, repository, bootstrap, commands.size());
    }
    properties.put("hibernate.hbm2ddl.auto", "validate");
    try (HibernateSessionFactoryProvider reopened =
        new HibernateSessionFactoryProvider(properties)) {
      assertTrue(
          new HibernateReflogBatchProcessor(reopened.getSessionFactory())
              .execute(repository, commands).stream()
                  .allMatch(
                      result -> result.status() == ReflogAppendResult.Status.ALREADY_APPLIED));
      assertBootstrapPreserved(reopened, repository, bootstrap, commands.size());
    }
  }

  private static ReflogAppendCommand command(
      String delivery, int oldId, int newId, String message) {
    return new ReflogAppendCommand(
        delivery,
        "refs/heads/main",
        ObjectId.fromString("%040x".formatted(oldId)),
        ObjectId.fromString("%040x".formatted(newId)),
        "Fixture",
        "fixture@example.invalid",
        Instant.parse("2026-11-04T00:00:00Z"),
        message);
  }

  private static void executeSql(HibernateSessionFactoryProvider provider, String sql) {
    try (Session session = provider.getSessionFactory().openSession()) {
      var transaction = session.beginTransaction();
      session.createNativeMutationQuery(sql).executeUpdate();
      transaction.commit();
    }
  }

  private static Properties properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:oracle-fixture-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
