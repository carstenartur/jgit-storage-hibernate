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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PackRepackMaintenanceConcurrencyH2Test {

  private static final int COMMIT_COUNT = 24;
  private static final int PAYLOAD_BYTES = 16 * 1024;
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void readersObserveACompleteGenerationWhileRepackIsRunning() throws Exception {
    String repositoryName = "concurrent-reader-" + UUID.randomUUID();

    try (HibernateSessionFactoryProvider provider = provider()) {
      HistoryFixture fixture = createFixture(provider, repositoryName, 0x16500000L);
      BlockingProgressMonitor monitor = new BlockingProgressMonitor();

      try (HibernateRepository reader =
              HibernateRepository.create(provider.getSessionFactory(), repositoryName);
          ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<PackRepackResult> maintenance =
            executor.submit(
                () ->
                    new PackStorageMaintenance(provider.getSessionFactory())
                        .repack(
                            new RepositoryName(repositoryName),
                            PackRepackOptions.optimizedForReads(),
                            monitor));

        assertTrue(
            monitor.awaitBlocked(20, TimeUnit.SECONDS),
            "Repack did not reach a deterministic active phase");

        CountDownLatch firstRead = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        Future<Integer> reads =
            executor.submit(
                () -> {
                  int completed = 0;
                  while (!stop.get()) {
                    assertSnapshot(reader, fixture);
                    completed++;
                    firstRead.countDown();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                  }
                  assertSnapshot(reader, fixture);
                  return completed;
                });

        try {
          assertTrue(
              firstRead.await(20, TimeUnit.SECONDS),
              "A reader must complete while maintenance is paused");
          assertFalse(maintenance.isDone(), "Maintenance must still be active");
          monitor.release();

          PackRepackResult result = maintenance.get(60, TimeUnit.SECONDS);
          assertTrue(result.successful());
          assertTrue(result.packsAfter() < result.packsBefore());
        } finally {
          monitor.release();
          stop.set(true);
        }

        assertTrue(reads.get(20, TimeUnit.SECONDS) > 0);
        assertSnapshot(reader, fixture);
      }

      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        assertSnapshot(reopened, fixture);
      }
    }
  }

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void maintenanceOfIndependentRepositoriesDoesNotUseAGlobalLock() throws Exception {
    String repositoryA = "concurrent-maintenance-a-" + UUID.randomUUID();
    String repositoryB = "concurrent-maintenance-b-" + UUID.randomUUID();

    try (HibernateSessionFactoryProvider provider = provider()) {
      HistoryFixture fixtureA = createFixture(provider, repositoryA, 0x16510000L);
      HistoryFixture fixtureB = createFixture(provider, repositoryB, 0x16520000L);
      BlockingProgressMonitor monitorA = new BlockingProgressMonitor();

      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<PackRepackResult> maintenanceA =
            executor.submit(
                () ->
                    new PackStorageMaintenance(provider.getSessionFactory())
                        .repack(
                            new RepositoryName(repositoryA),
                            PackRepackOptions.optimizedForReads(),
                            monitorA));

        assertTrue(
            monitorA.awaitBlocked(20, TimeUnit.SECONDS),
            "First repository did not reach a deterministic active phase");

        Future<PackRepackResult> maintenanceB =
            executor.submit(
                () ->
                    new PackStorageMaintenance(provider.getSessionFactory())
                        .repack(
                            new RepositoryName(repositoryB),
                            PackRepackOptions.optimizedForReads(),
                            NullProgressMonitor.INSTANCE));

        try {
          PackRepackResult resultB = maintenanceB.get(60, TimeUnit.SECONDS);
          assertTrue(resultB.successful());
          assertTrue(resultB.packsAfter() < resultB.packsBefore());
          assertFalse(
              maintenanceA.isDone(),
              "Independent maintenance must finish while the first repository remains paused");
        } finally {
          monitorA.release();
        }

        PackRepackResult resultA = maintenanceA.get(60, TimeUnit.SECONDS);
        assertTrue(resultA.successful());
        assertTrue(resultA.packsAfter() < resultA.packsBefore());
      }

      try (HibernateRepository reopenedA =
              HibernateRepository.create(provider.getSessionFactory(), repositoryA);
          HibernateRepository reopenedB =
              HibernateRepository.create(provider.getSessionFactory(), repositoryB)) {
        assertSnapshot(reopenedA, fixtureA);
        assertSnapshot(reopenedB, fixtureB);
      }
    }
  }

  private static HistoryFixture createFixture(
      HibernateSessionFactoryProvider provider, String repositoryName, long seed)
      throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HistoryFixture fixture = writeHistory(repository, seed);
      assertTrue(
          repository.getObjectDatabase().getPacks().length > 4,
          "The concurrency fixture must contain multiple incremental packs");
      assertSnapshot(repository, fixture);
      return fixture;
    }
  }

  private static HistoryFixture writeHistory(HibernateRepository repository, long seed)
      throws Exception {
    ObjectId parent = null;
    ObjectId oldestBlob = null;
    for (int index = 0; index < COMMIT_COUNT; index++) {
      ObjectId blob;
      ObjectId commitId;
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        new Random(seed + index).nextBytes(payload);
        blob = inserter.insert(Constants.OBJ_BLOB, payload);
        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(inserter.insert(tree));
        if (parent != null) {
          commit.setParentId(parent);
        }
        PersonIdent identity =
            new PersonIdent(
                "Concurrent Maintenance Test",
                "maintenance-concurrency@example.invalid",
                Date.from(Instant.ofEpochSecond(1_760_000_000L + index)),
                UTC);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Incremental concurrency commit " + index);
        commitId = inserter.insert(commit);
        inserter.flush();
      }

      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setExpectedOldObjectId(parent == null ? ObjectId.zeroId() : parent);
      update.setNewObjectId(commitId);
      update.disableRefLog();
      RefUpdate.Result updateResult = update.update();
      assertTrue(
          updateResult == RefUpdate.Result.NEW
              || updateResult == RefUpdate.Result.FAST_FORWARD,
          () -> "Unexpected ref update result " + updateResult);
      if (oldestBlob == null) {
        oldestBlob = blob;
      }
      parent = commitId;
    }
    return new HistoryFixture(parent, oldestBlob);
  }

  private static void assertSnapshot(
      HibernateRepository repository, HistoryFixture fixture) throws Exception {
    Ref main = repository.exactRef(Constants.R_HEADS + "main");
    assertNotNull(main);
    assertEquals(fixture.tip(), main.getObjectId());

    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader oldest = reader.open(fixture.oldestBlob(), Constants.OBJ_BLOB);
      assertEquals(PAYLOAD_BYTES, oldest.getSize());
    }

    int count = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(fixture.tip()));
      for (RevCommit ignored : walk) {
        count++;
      }
    }
    assertEquals(COMMIT_COUNT, count);
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:repack-concurrency-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "8");
    return new HibernateSessionFactoryProvider(properties);
  }

  private record HistoryFixture(ObjectId tip, ObjectId oldestBlob) {}

  private static final class BlockingProgressMonitor implements ProgressMonitor {

    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean blocking = new AtomicBoolean();

    @Override
    public void start(int totalTasks) {
      // Nothing to report.
    }

    @Override
    public void beginTask(String title, int totalWork) {
      if (!blocking.compareAndSet(false, true)) {
        return;
      }
      blocked.countDown();
      try {
        if (!release.await(60, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to resume maintenance");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while maintenance was paused", interrupted);
      }
    }

    @Override
    public void update(int completed) {
      // Nothing to report.
    }

    @Override
    public void endTask() {
      // Nothing to report.
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public void showDuration(boolean enabled) {
      // Nothing to report.
    }

    boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
      return blocked.await(timeout, unit);
    }

    void release() {
      release.countDown();
    }
  }
}
