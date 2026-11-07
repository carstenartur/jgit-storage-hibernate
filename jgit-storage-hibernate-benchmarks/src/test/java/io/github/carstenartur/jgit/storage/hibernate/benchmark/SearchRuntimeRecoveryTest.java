/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildProgress;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildState;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.search.backend.lucene.lowlevel.directory.spi.DirectoryHolder;
import org.hibernate.search.backend.lucene.lowlevel.directory.spi.DirectoryProvider;
import org.hibernate.search.engine.environment.bean.BeanReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Process and I/O failure checks against the same projection code and Lucene backend as JMH. */
class SearchRuntimeRecoveryTest {
  private static final String REPOSITORY = "runtime-recovery";
  private static final String REF = "refs/heads/main";
  private static final String ASYNC = "sync-async-r500";
  private static final String INJECTED_FAILURE = "Injected Lucene commit sync failure";
  private static final int TIMEOUT_SECONDS = 30;

  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(strings = {"reference", "sync-sync-r0"})
  void acknowledgedIndexWritesSurviveAbruptProcessTermination(String scenario) throws Exception {
    List<String> history = seed();
    crashWriter(scenario, "2");

    try (Fixture fixture =
        open(root, "reference", false, null, new RuntimeBenchmarkFailureHandler())) {
      assertEquals(Set.copyOf(history), fixture.projectedIds());
      assertEquals(history.stream().sorted().toList(), fixture.visibleIds());
      assertEquals(history.getLast(), fixture.head().name());
    }
  }

  @Test
  void asynchronousAcknowledgmentCanLeaveRowsAheadOfTheIndexAfterACrash() throws Exception {
    List<String> history = seed();
    crashWriter(ASYNC, "2");

    try (Fixture fixture =
        open(root, "reference", false, null, new RuntimeBenchmarkFailureHandler())) {
      assertEquals(Set.copyOf(history), fixture.projectedIds());
      assertEquals(List.of(history.getFirst()), fixture.visibleIds());
      assertEquals(0, fixture.indexBurst(), "Incremental indexing skips rows already persisted");
      assertEquals(List.of(history.getFirst()), fixture.visibleIds());
      assertRebuilt(fixture, history, 3);
    }
  }

  @Test
  void interruptedRebuildKeepsItsCommittedBatchAndCanBeRetriedAfterRestart() throws Exception {
    List<String> history = seed();
    crashWriter("interrupt-rebuild", "INTERRUPTED:1");

    try (Fixture fixture =
        open(root, "reference", false, null, new RuntimeBenchmarkFailureHandler())) {
      assertEquals(Set.of(history.getFirst()), fixture.projectedIds());
      assertEquals(List.of(history.getFirst()), fixture.visibleIds());
      assertRebuilt(fixture, history, 1);
    }
  }

  @Test
  void actualAsyncLuceneFailureReachesOnlyTheOwningBenchmarkHandler() throws Exception {
    List<String> history = seed();
    RuntimeBenchmarkFailureHandler handler = new RuntimeBenchmarkFailureHandler();
    RuntimeBenchmarkFailureHandler otherInvocation = new RuntimeBenchmarkFailureHandler();
    CommitBarrier barrier = new CommitBarrier(true);

    try (Fixture fixture = open(root, ASYNC, false, barrier, handler)) {
      barrier.arm();
      try {
        assertEquals(2, fixture.indexBurst(), "Async submission returned before Lucene sync");
        barrier.awaitArrival();
        assertEquals(Set.copyOf(history), fixture.projectedIds());
        handler.assertNoFailure();
      } finally {
        barrier.release();
      }
      IllegalStateException failure = awaitFailure(handler);
      assertTrue(hasCause(failure, INJECTED_FAILURE), () -> "Unexpected failure: " + failure);
      assertDoesNotThrow(otherInvocation::assertNoFailure);
    }
  }

  @Test
  void writeSyncReportsACommitFailureToTheCallerEvenAfterTheRowsCommit() throws Exception {
    List<String> history = seed();
    RuntimeBenchmarkFailureHandler handler = new RuntimeBenchmarkFailureHandler();
    CommitBarrier barrier = new CommitBarrier(true);

    try (Fixture fixture = open(root, "reference", false, barrier, handler)) {
      barrier.arm();
      barrier.release();
      RuntimeException failure = assertThrows(RuntimeException.class, fixture::indexBurst);
      assertTrue(hasCause(failure, INJECTED_FAILURE), () -> "Unexpected failure: " + failure);
      assertEquals(Set.copyOf(history), fixture.projectedIds());
    }
  }

  private List<String> seed() throws Exception {
    try (Fixture fixture =
        open(root, "reference", true, null, new RuntimeBenchmarkFailureHandler())) {
      fixture.repository().create(true);
      List<String> history = new ArrayList<>();
      ObjectId parent = null;
      try (var inserter = fixture.repository().newObjectInserter()) {
        ObjectId tree = inserter.insert(new TreeFormatter());
        for (int i = 0; i < 3; i++) {
          CommitBuilder commit = new CommitBuilder();
          commit.setTreeId(tree);
          if (parent != null) {
            commit.setParentId(parent);
          }
          PersonIdent identity =
              new PersonIdent(
                  "Recovery fixture",
                  "recovery@example.invalid",
                  Instant.ofEpochSecond(i),
                  ZoneOffset.UTC);
          commit.setAuthor(identity);
          commit.setCommitter(identity);
          commit.setMessage("recoveryneedle commit " + i);
          parent = inserter.insert(commit);
          history.add(parent.name());
        }
        inserter.flush();
      }
      RefUpdate update = fixture.repository().updateRef(REF);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(parent);
      update.disableRefLog();
      assertEquals(RefUpdate.Result.NEW, update.update());
      fixture.indexer().indexCommit(fixture.repository(), ObjectId.fromString(history.getFirst()));
      assertEquals(List.of(history.getFirst()), fixture.visibleIds());
      return List.copyOf(history);
    }
  }

  private void crashWriter(String mode, String expected) throws Exception {
    Path ready = root.resolve("writer-ready");
    Path log = root.resolve("writer.log");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-cp",
                classpath,
                Writer.class.getName(),
                root.toString(),
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      long deadline = System.nanoTime() + SECONDS.toNanos(TIMEOUT_SECONDS);
      while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(Files.exists(ready), () -> "Writer did not reach checkpoint:\n" + readLog(log));
      assertTrue(child.isAlive(), "Writer must still own the open SessionFactory");
      assertEquals(expected, Files.readString(ready));
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(TIMEOUT_SECONDS, SECONDS), "Writer did not terminate");
    }
  }

  private static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException failure) {
      return failure.toString();
    }
  }

  private static IllegalStateException awaitFailure(RuntimeBenchmarkFailureHandler handler)
      throws InterruptedException {
    long deadline = System.nanoTime() + SECONDS.toNanos(TIMEOUT_SECONDS);
    do {
      try {
        handler.assertNoFailure();
      } catch (IllegalStateException failure) {
        return failure;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Hibernate Search did not call the background failure handler");
  }

  private static boolean hasCause(Throwable failure, String expected) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (expected.equals(cause.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private static void assertRebuilt(Fixture fixture, List<String> history, int removed)
      throws Exception {
    var result = fixture.rebuild().rebuild(fixture.repository(), new RepositoryName(REPOSITORY));
    assertEquals(RebuildState.COMPLETED, result.state());
    assertEquals(removed, result.removedProjections());
    assertEquals(3, result.indexedCommits());
    assertEquals(Set.copyOf(history), fixture.projectedIds());
    assertEquals(history.stream().sorted().toList(), fixture.visibleIds());
    assertEquals(history.getLast(), fixture.head().name());
  }

  private static Fixture open(
      Path root,
      String scenario,
      boolean create,
      CommitBarrier barrier,
      RuntimeBenchmarkFailureHandler handler)
      throws IOException {
    Path index = root.resolve("index");
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:hsqldb:file:" + root.resolve("database") + ";hsqldb.write_delay=false;shutdown=true");
    properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.put("hibernate.connection.username", "SA");
    properties.put("hibernate.hbm2ddl.auto", create ? "create" : "validate");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.schema_management.strategy", create ? "create" : "validate");
    properties.put(SearchIndexingProfile.PROFILE_PROPERTY, SearchIndexingProfile.CONTENT.id());
    properties.put(
        "hibernate.search.background_failure_handler", BeanReference.ofInstance(handler));
    SearchRuntimeScenario.fromId(scenario).apply(properties);
    if (barrier == null) {
      properties.put("hibernate.search.backend.directory.type", "local-filesystem");
      properties.put("hibernate.search.backend.directory.root", index.toString());
    } else {
      properties.put(
          "hibernate.search.backend.directory.type",
          BeanReference.ofInstance(barrier.provider(index)));
    }
    Logger logger =
        Logger.getLogger("org.hibernate.engine.jdbc.env.internal.JdbcEnvironmentInitiator");
    Level previousLevel = logger.getLevel();
    HibernateSessionFactoryProvider provider;
    try {
      logger.setLevel(Level.WARNING);
      provider = new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
    } finally {
      logger.setLevel(previousLevel);
    }
    try {
      return new Fixture(
          provider, HibernateRepository.create(provider.getSessionFactory(), REPOSITORY));
    } catch (IOException | RuntimeException failure) {
      provider.close();
      throw failure;
    }
  }

  private record Fixture(HibernateSessionFactoryProvider provider, HibernateRepository repository)
      implements AutoCloseable {
    ObjectId head() throws IOException {
      return repository.exactRef(REF).getObjectId();
    }

    CommitIndexer indexer() {
      return new CommitIndexer(provider.getSessionFactory(), REPOSITORY);
    }

    int indexBurst() throws IOException {
      return indexer().indexCommitsFrom(repository, head(), 2);
    }

    CommitProjectionRebuilder rebuild() {
      return new CommitProjectionRebuilder(provider.getSessionFactory());
    }

    Set<String> projectedIds() {
      try (var session = provider.getSessionFactory().openSession()) {
        return Set.copyOf(
            session
                .createSelectionQuery(
                    "select objectId from GitCommitIndex where repositoryName = :name",
                    String.class)
                .setParameter("name", REPOSITORY)
                .getResultList());
      }
    }

    List<String> visibleIds() {
      return new GitHistorySearchService(provider.getSessionFactory())
          .searchCommitTextSummaries(REPOSITORY, "recoveryneedle", 10).stream()
              .map(hit -> hit.objectId())
              .sorted()
              .toList();
    }

    @Override
    public void close() {
      try (provider;
          repository) {
        // Close the repository before the session factory.
      }
    }
  }

  /**
   * Pauses a real filesystem commit before Lucene syncs data and publishes the new commit point.
   */
  private static final class CommitBarrier {
    private final boolean fail;
    private final CountDownLatch arrival = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean armed;

    CommitBarrier(boolean fail) {
      this.fail = fail;
    }

    void arm() {
      armed = true;
    }

    void awaitArrival() throws InterruptedException {
      assertTrue(arrival.await(TIMEOUT_SECONDS, SECONDS), "Lucene did not attempt to sync files");
    }

    void release() {
      release.countDown();
    }

    DirectoryProvider provider(Path root) {
      return context ->
          new DirectoryHolder() {
            private Directory directory;

            @Override
            public void start() throws IOException {
              directory =
                  new FilterDirectory(FSDirectory.open(root.resolve(context.indexName()))) {
                    @Override
                    public void sync(Collection<String> names) throws IOException {
                      if (armed) {
                        arrival.countDown();
                        try {
                          if (!release.await(2L * TIMEOUT_SECONDS, SECONDS)) {
                            throw new IOException(
                                "Timed out waiting for the Lucene sync test barrier");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IOException(
                              "Interrupted at the Lucene sync test barrier", interrupted);
                        }
                        if (fail) {
                          throw new IOException(INJECTED_FAILURE);
                        }
                      }
                      super.sync(names);
                    }
                  };
            }

            @Override
            public Directory get() {
              return directory;
            }

            @Override
            public void close() throws IOException {
              directory.close();
            }
          };
    }
  }

  /** A child JVM that never closes the index writer before the test terminates it. */
  public static final class Writer {
    public static void main(String[] args) throws Exception {
      Path root = Path.of(args[0]);
      String mode = args[1];
      boolean interrupt = "interrupt-rebuild".equals(mode);
      CommitBarrier barrier = ASYNC.equals(mode) ? new CommitBarrier(false) : null;
      var handler = new RuntimeBenchmarkFailureHandler();
      try (Fixture fixture = open(root, interrupt ? "batch-1" : mode, false, barrier, handler)) {
        String checkpoint;
        if (interrupt) {
          AtomicReference<RebuildProgress> last = new AtomicReference<>();
          try {
            assertThrows(
                InterruptedIOException.class,
                () ->
                    fixture
                        .rebuild()
                        .rebuild(
                            fixture.repository(),
                            new RepositoryName(REPOSITORY),
                            progress -> {
                              last.set(progress);
                              if (progress.state() == RebuildState.INDEXING
                                  && progress.indexedCommits() == 1) {
                                Thread.currentThread().interrupt();
                              }
                            }));
          } finally {
            Thread.interrupted();
          }
          checkpoint = last.get().state() + ":" + last.get().indexedCommits();
        } else {
          if (barrier != null) {
            barrier.arm();
          }
          checkpoint = Integer.toString(fixture.indexBurst());
          if (barrier != null) {
            barrier.awaitArrival();
          } else {
            assertEquals(3, fixture.visibleIds().size(), "First query after acknowledged writes");
          }
        }
        handler.assertNoFailure();
        Path pending = Files.writeString(root.resolve("writer-pending"), checkpoint);
        Files.move(pending, root.resolve("writer-ready"), StandardCopyOption.ATOMIC_MOVE);
        new CountDownLatch(1).await();
      }
    }
  }
}
