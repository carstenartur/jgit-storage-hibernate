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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.store.LockObtainFailedException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the native Lucene lock across application JVMs against a real index directory. */
class LuceneSingleWriterProcessH2Test {

  private static final String REPOSITORY = "exclusive-writer";
  private static final String FIRST = "1".repeat(40);
  private static final String SECOND = "2".repeat(40);

  @TempDir Path temporaryDirectory;

  @Test
  void rejectsASecondProcessThenRecoversTheCommittedIndexAfterTheOwnerDies() throws Exception {
    String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
    String classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path ownerLog = temporaryDirectory.resolve("owner.log");
    Process owner =
        new ProcessBuilder(
                java.toString(),
                "-cp",
                classPath,
                getClass().getName(),
                temporaryDirectory.toString())
            .redirectErrorStream(true)
            .redirectOutput(ownerLog.toFile())
            .start();
    try {
      Path ready = temporaryDirectory.resolve("owner.ready");
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (!Files.exists(ready) && owner.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      assertTrue(Files.exists(ready), () -> "Index owner failed to start: " + readLog(ownerLog));

      // Use an independent H2 database so that its lock cannot be mistaken for the Lucene lock.
      RuntimeException failure =
          assertThrows(
              RuntimeException.class,
              () -> {
                try (var contender = provider(temporaryDirectory, "contender", "create")) {
                  persist(contender.getSessionFactory(), SECOND);
                }
              });
      assertTrue(hasLuceneLockFailure(failure), () -> "Expected a Lucene lock failure: " + failure);
    } finally {
      owner.destroyForcibly();
      assertTrue(owner.waitFor(15, TimeUnit.SECONDS), "Index owner did not stop");
    }

    try (var restarted = provider(temporaryDirectory, "owner", "validate")) {
      var search = new GitHistorySearchService(restarted.getSessionFactory());
      assertEquals(
          List.of(FIRST),
          search.searchCommitText(REPOSITORY, "committed", 10).stream()
              .map(GitCommitIndex::getObjectId)
              .toList());
      persist(restarted.getSessionFactory(), SECOND);
      assertEquals(2, search.searchCommitTextSummaries(REPOSITORY, "committed", 10).size());
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    try (var provider = provider(root, "owner", "create")) {
      persist(provider.getSessionFactory(), FIRST);
      Files.createFile(root.resolve("owner.ready"));
      new CountDownLatch(1).await();
    }
  }

  private static boolean hasLuceneLockFailure(Throwable failure) {
    if (failure == null) {
      return false;
    }
    if (failure instanceof LockObtainFailedException || hasLuceneLockFailure(failure.getCause())) {
      return true;
    }
    for (Throwable suppressed : failure.getSuppressed()) {
      if (hasLuceneLockFailure(suppressed)) {
        return true;
      }
    }
    return false;
  }

  private static String readLog(Path path) {
    try {
      return Files.readString(path);
    } catch (java.io.IOException unavailable) {
      return unavailable.toString();
    }
  }

  private static void persist(SessionFactory factory, String objectId) {
    try (var session = factory.openSession()) {
      session.beginTransaction();
      var row = new GitCommitIndex();
      row.setRepositoryName(REPOSITORY);
      row.setObjectId(objectId);
      row.setShortMessage("committed projection");
      row.setFullMessage("committed projection");
      row.setCommitterTime(Instant.EPOCH);
      session.persist(row);
      session.getTransaction().commit();
    }
  }

  private static HibernateSessionFactoryProvider provider(
      Path root, String database, String schema) {
    Properties properties = new Properties();
    properties.setProperty(
        "hibernate.connection.url", "jdbc:h2:file:" + root.resolve(database) + ";WRITE_DELAY=0");
    properties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    properties.setProperty("hibernate.hbm2ddl.auto", schema);
    properties.setProperty("hibernate.search.backend.type", "lucene");
    properties.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
    properties.setProperty(
        "hibernate.search.backend.directory.root", root.resolve("lucene").toString());
    properties.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }
}
