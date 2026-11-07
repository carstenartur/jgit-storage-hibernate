/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.objects.ReadAheadHibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;

class PackRefPublicationConcurrencyH2Test {

  private static final String REF = "refs/heads/concurrent-publication";
  private static final long TIMEOUT_SECONDS = 10;

  @Test
  void publishesPackAndRefOnTheSameRepositoryWithoutLockInversion() throws Exception {
    CountDownLatch refHasDatabaseLock = new CountDownLatch(1);
    CountDownLatch finishRefUpdate = new CountDownLatch(1);
    HibernateSessionFactoryProvider provider = provider();
    ExecutorService workers =
        Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    HibernateRepository repository = null;
    try {
      HibernateRepository shared =
          HibernateRepository.create(
              provider.getSessionFactory(),
              "pack-ref-publication",
              request -> {
                if (REF.equals(request.refName())) {
                  refHasDatabaseLock.countDown();
                  await(finishRefUpdate, "The ref update was not released");
                }
              });
      repository = shared;
      ObjectId commit = insertCommit(shared);
      byte[] payload = {1, 2, 3};
      CountDownLatch packStaged = new CountDownLatch(1);
      AtomicReference<Thread> packPublisher = new AtomicReference<>();
      Future<ObjectId> pack =
          workers.submit(
              () -> {
                try (ObjectInserter inserter = shared.newObjectInserter()) {
                  ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);
                  packPublisher.set(Thread.currentThread());
                  packStaged.countDown();
                  await(refHasDatabaseLock, "The ref update did not acquire the database lock");
                  inserter.flush();
                  return blob;
                }
              });
      await(packStaged, "The pack was not staged");
      Future<RefUpdate.Result> ref = workers.submit(() -> updateRef(shared, commit));
      await(refHasDatabaseLock, "The ref update did not acquire the database lock");

      // Both paths must overlap while the ref transaction already owns the database row lock.
      awaitBlockedPackPublication(packPublisher.get());
      finishRefUpdate.countDown();

      RefUpdate.Result result = ref.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      ObjectId blob = pack.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(RefUpdate.Result.NEW, result);
      assertEquals(commit, shared.exactRef(REF).getObjectId());
      assertArrayEquals(payload, shared.open(blob).getBytes());
    } finally {
      finishRefUpdate.countDown();
      workers.shutdownNow();
      // A deadlocked daemon worker may still hold the Reftable lock needed by close().
      if (workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        try {
          if (repository != null) {
            repository.close();
          }
        } finally {
          provider.close();
        }
      }
    }
  }

  private static void awaitBlockedPackPublication(Thread publisher) throws InterruptedException {
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      ThreadInfo info = threads.getThreadInfo(publisher.threadId(), 128);
      if (info != null) {
        Thread.State state = info.getThreadState();
        if ((state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING
                || state == Thread.State.BLOCKED)
            && hasFrame(info, HibernateTransactionContext.class, "acquireRepositoryLock")
            && hasFrame(info, ReadAheadHibernateObjDatabase.class, "commitPackImpl")) {
          return;
        }
      }
      Thread.sleep(1);
    }
    throw new AssertionError("Pack publication did not wait for the overlapping ref update");
  }

  private static boolean hasFrame(ThreadInfo thread, Class<?> type, String method) {
    return Arrays.stream(thread.getStackTrace())
        .anyMatch(
            frame ->
                frame.getClassName().equals(type.getName()) && frame.getMethodName().equals(method));
  }

  private static void await(CountDownLatch latch, String message) {
    try {
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(message, interrupted);
    }
  }

  private static RefUpdate.Result updateRef(HibernateRepository repository, ObjectId commit)
      throws IOException {
    RefUpdate update = repository.updateRef(REF);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(commit);
    return update.update();
  }

  private static ObjectId insertCommit(HibernateRepository repository) throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      PersonIdent author =
          new PersonIdent("Test", "test@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage("Initial commit");
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:pack-ref-publication-" + UUID.randomUUID() + ";LOCK_TIMEOUT=3000");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.connection.pool_size", "4");
    return new HibernateSessionFactoryProvider(properties);
  }
}
