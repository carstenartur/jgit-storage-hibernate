/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.hibernate.search.backend.lucene.lowlevel.directory.spi.DirectoryHolder;
import org.hibernate.search.backend.lucene.lowlevel.directory.spi.DirectoryProvider;
import org.hibernate.search.engine.environment.bean.BeanReference;
import org.hibernate.search.mapper.orm.Search;

/** Bounded correctness and operational diagnostic; deliberately separate from the JMH matrix. */
final class SearchRuntimeSustainedDiagnostic {
  private static final String NAME = "runtime-sustained-diagnostic";
  private static final String REF = "refs/heads/main";
  private static final List<String> RAW_FILES =
      List.of(
          "metadata.properties",
          "completion.properties",
          "samples.csv",
          "transactions.csv",
          "queries.csv",
          "trace.csv",
          "membership.csv");

  record Options(
      String scenario,
      String resources,
      boolean traced,
      int seedCommits,
      int batchSize,
      int sustainedBatches,
      int overloadBatches,
      int periodMillis,
      int gateHoldMillis,
      int timeoutSeconds) {
    Options {
      if (!Set.of("reference", "sync-async-r500").contains(scenario))
        throw new IllegalArgumentException("Unsupported diagnostic synchronization mode");
      if (seedCommits < 10
          || seedCommits > 1000
          || batchSize < 1
          || batchSize > 50
          || sustainedBatches < 2
          || sustainedBatches > 200
          || overloadBatches < 10
          || overloadBatches > 200
          || periodMillis < 1
          || periodMillis > 1000
          || (long) sustainedBatches * periodMillis > 30_000L
          || gateHoldMillis < 100
          || gateHoldMillis > 2000
          || timeoutSeconds < 2
          || timeoutSeconds > 60
          || seedCommits + (sustainedBatches + overloadBatches) * batchSize > 20_000)
        throw new IllegalArgumentException("Diagnostic limits are outside their bounded ranges");
    }

    int totalCommits() {
      return seedCommits + (sustainedBatches + overloadBatches) * batchSize;
    }

    int maxBufferedDocs() {
      return Math.max(2, 2 * batchSize);
    }

    Duration timeout() {
      return Duration.ofSeconds(timeoutSeconds);
    }
  }

  static void run(Properties connection, Path index, Path output, Options options)
      throws Exception {
    Files.createDirectories(output);
    try (var entries = Files.list(output)) {
      if (entries.findAny().isPresent())
        throw new IOException("Diagnostic output must be empty: " + output);
    }
    var metadata = metadata(options);
    var completion = new Properties();
    var gate = new SearchRuntimeOutputGate(options.timeout());
    var handler = new RuntimeBenchmarkFailureHandler();
    List<ObjectId> history;
    try (var log = new SearchRuntimeDiagnosticLog(output, options.traced())) {
      try (var fixture = open(connection, index, options, true, gate, handler)) {
        captureDatabaseMetadata(fixture, metadata);
        writeProperties(output.resolve("metadata.properties"), metadata);
        history = createHistory(fixture.repository, options);
        advanceRef(fixture.repository, history.get(options.seedCommits() - 1));
        int seeded =
            fixture.indexer.indexCommitsFrom(
                fixture.repository, history.get(options.seedCommits() - 1), options.seedCommits());
        require(seeded == options.seedCommits(), "Seed projection count differs");
        awaitVisible(fixture, options.seedCommits(), options.timeout(), handler, log);

        var progress =
            new Progress(
                options.seedCommits(),
                log.elapsed(),
                Set.copyOf(
                    history.subList(0, options.seedCommits()).stream()
                        .map(ObjectId::name)
                        .toList()));
        var queryStop = new AtomicBoolean();
        var producerThread = new AtomicReference<Thread>();
        try (var workers = new SearchRuntimeDiagnosticWorkers(gate, options.timeout())) {
          fixture.activeWorkers = workers::active;
          Future<?> query =
              workers.submit(
                  () -> {
                    while (!queryStop.get()) {
                      String phase = log.phase();
                      long started = log.elapsed();
                      var hits = fixture.search.searchCommitTextSummaries(NAME, "steadyneedle", 10);
                      long completed = log.elapsed();
                      require(
                          hits.size() == Math.min(10, options.seedCommits()),
                          "Stable query lost seed hits");
                      for (var hit : hits)
                        require(
                            progress.seedIds.contains(hit.objectId()),
                            "Stable query hit is not a seed");
                      int visible = fixture.visibleCount();
                      long observed = log.elapsed();
                      progress.visibility.set(new VisibilityObservation(visible, observed));
                      progress.queries.incrementAndGet();
                      log.queries.row(phase, started, completed, hits.size(), visible, observed);
                      Thread.sleep(20L);
                    }
                    return null;
                  });
          log.phase("sustained");
          Future<?> sustained =
              workers.submit(
                  () ->
                      submitBatches(
                          fixture,
                          history,
                          options.seedCommits(),
                          options.sustainedBatches(),
                          options.periodMillis(),
                          "sustained",
                          options,
                          progress,
                          producerThread,
                          log));
          waitFor(
              sustained,
              workers,
              fixture,
              options,
              progress,
              gate,
              producerThread,
              log,
              handler,
              options
                  .timeout()
                  .plusMillis((long) options.sustainedBatches() * options.periodMillis()));
          log.phase("drain-sustained");
          long drain = System.nanoTime();
          int sustainedCount =
              options.seedCommits() + options.sustainedBatches() * options.batchSize();
          awaitVisible(fixture, sustainedCount, options.timeout(), handler, log);
          completion.setProperty("drain.sustained.ns", Long.toString(System.nanoTime() - drain));
          require(
              progress.acknowledged.get() == sustainedCount,
              "Sustained acknowledgements incomplete");

          log.phase("overload");
          gate.arm();
          Future<?> overloaded =
              workers.submit(
                  () ->
                      submitBatches(
                          fixture,
                          history,
                          sustainedCount,
                          options.overloadBatches(),
                          0,
                          "overload",
                          options,
                          progress,
                          producerThread,
                          log));
          boolean queueBackpressure = false;
          boolean incomplete;
          long heldStarted;
          try {
            workers.awaitGate(
                () -> {
                  handler.assertNoFailure();
                  log.assertHealthy();
                });
            heldStarted = System.nanoTime();
            long deadline = heldStarted + options.timeout().toNanos();
            do {
              workers.assertHealthy();
              handler.assertNoFailure();
              String wait = producerWait(producerThread.get());
              queueBackpressure |= "queue".equals(wait);
              sample(progress, workers, gate, wait, log);
              incomplete =
                  !overloaded.isDone() && progress.submitted.get() > progress.acknowledged.get();
              boolean heldLongEnough =
                  System.nanoTime() - heldStarted
                      >= TimeUnit.MILLISECONDS.toNanos(options.gateHoldMillis());
              if (heldLongEnough
                  && incomplete
                  && (!options.scenario().equals("sync-async-r500") || queueBackpressure)) break;
              require(
                  !overloaded.isDone(),
                  "Producer completed during the gate; overload was not established");
              require(
                  System.nanoTime() < deadline,
                  "No blocked queue submission was observed before the diagnostic timeout");
              Thread.sleep(10L);
            } while (true);
            completion.setProperty("gate.entered", Boolean.toString(gate.entered()));
            completion.setProperty("gate.producer.incomplete", Boolean.toString(incomplete));
            completion.setProperty(
                "gate.queue.backpressure.observed", Boolean.toString(queueBackpressure));
            completion.setProperty(
                "gate.submitted.commits", Integer.toString(progress.submitted.get()));
            completion.setProperty(
                "gate.acknowledged.commits", Integer.toString(progress.acknowledged.get()));
          } finally {
            gate.release();
          }
          completion.setProperty(
              "gate.held.ns", Long.toString(gate.releasedAt() - gate.enteredAt()));
          completion.setProperty(
              "gate.entered.elapsed.ns", Long.toString(log.timestamp(gate.enteredAt())));
          completion.setProperty(
              "gate.released.elapsed.ns", Long.toString(log.timestamp(gate.releasedAt())));
          log.phase("drain-overload");
          drain = System.nanoTime();
          waitFor(
              overloaded,
              workers,
              fixture,
              options,
              progress,
              gate,
              producerThread,
              log,
              handler,
              options.timeout());
          awaitVisible(fixture, options.totalCommits(), options.timeout(), handler, log);
          completion.setProperty("drain.overload.ns", Long.toString(System.nanoTime() - drain));
          queryStop.set(true);
          query.get(options.timeoutSeconds(), TimeUnit.SECONDS);
          workers.assertHealthy();
          handler.assertNoFailure();
          log.assertHealthy();
        } finally {
          queryStop.set(true);
        }
        completion.setProperty(
            "workers.after.close", Integer.toString(fixture.activeWorkers.getAsInt()));
        require(fixture.activeWorkers.getAsInt() == 0, "Worker survived diagnostic close");
        log.phase("verify");
        verifyMembership(fixture, history, completion, false);
        log.phase("close");
      }
      handler.assertNoFailure();
      log.assertHealthy();
      log.phase("reopen");
      try (var reopened = open(connection, index, options, false, gate, handler)) {
        verifyMembership(reopened, history, completion, true);
      }
      handler.assertNoFailure();
      log.phase("closed");
      log.assertHealthy();
      completion.setProperty("trace.records", Long.toString(log.traceRecords()));
    }
    try (var membership =
        new SearchRuntimeDiagnosticLog.Csv(
            output.resolve("membership.csv"),
            "seq,object_id,sql_present,lucene_present,git_reachable,reopened_sql_present,reopened_lucene_present")) {
      for (ObjectId id : history) membership.row(id.name(), true, true, true, true, true);
    }
    completion.setProperty("expected.commits", Integer.toString(options.totalCommits()));
    completion.setProperty("handler.failures", "0");
    completion.setProperty("status", "complete");
    writeProperties(output.resolve("completion.properties"), completion);
    Properties hashes = new Properties();
    for (String file : RAW_FILES)
      hashes.setProperty(file, sha256(Files.readAllBytes(output.resolve(file))));
    writeProperties(output.resolve("sha256.properties"), hashes);
  }

  private static Void submitBatches(
      Fixture fixture,
      List<ObjectId> history,
      int first,
      int batches,
      int periodMillis,
      String phase,
      Options options,
      Progress progress,
      AtomicReference<Thread> producerThread,
      SearchRuntimeDiagnosticLog log)
      throws Exception {
    producerThread.set(Thread.currentThread());
    long start = System.nanoTime();
    for (int batch = 0; batch < batches; batch++) {
      long planned = start + TimeUnit.MILLISECONDS.toNanos((long) batch * periodMillis);
      long remaining;
      while ((remaining = planned - System.nanoTime()) > 0L) TimeUnit.NANOSECONDS.sleep(remaining);
      int last = first + options.batchSize() - 1;
      long begun = log.elapsed();
      long late = Math.max(0L, System.nanoTime() - planned);
      progress.submitted.addAndGet(options.batchSize());
      advanceRef(fixture.repository, history.get(last));
      int indexed =
          fixture.indexer.indexCommitsFrom(
              fixture.repository, history.get(last), options.batchSize());
      require(
          indexed == options.batchSize(),
          "Incremental transaction did not index its complete batch");
      progress.acknowledged.addAndGet(indexed);
      log.transactions.row(phase, begun, log.elapsed(), first, last, indexed, late);
      first = last + 1;
    }
    return null;
  }

  private static void waitFor(
      Future<?> future,
      SearchRuntimeDiagnosticWorkers workers,
      Fixture fixture,
      Options options,
      Progress progress,
      SearchRuntimeOutputGate gate,
      AtomicReference<Thread> producer,
      SearchRuntimeDiagnosticLog log,
      RuntimeBenchmarkFailureHandler handler,
      Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!future.isDone()) {
      workers.assertHealthy();
      handler.assertNoFailure();
      log.assertHealthy();
      sample(progress, workers, gate, producerWait(producer.get()), log);
      require(System.nanoTime() < deadline, "Producer exceeded diagnostic time limit");
      Thread.sleep(20L);
    }
    future.get(options.timeoutSeconds(), TimeUnit.SECONDS);
    sample(progress, workers, gate, "done", log);
  }

  private static void sample(
      Progress progress,
      SearchRuntimeDiagnosticWorkers workers,
      SearchRuntimeOutputGate gate,
      String wait,
      SearchRuntimeDiagnosticLog log)
      throws IOException {
    var observation = progress.visibility.get();
    int acknowledged = progress.acknowledged.get();
    int submitted = progress.submitted.get();
    long now = log.elapsed();
    long gcCount = 0L;
    long gcTime = 0L;
    var collectors = ManagementFactory.getGarbageCollectorMXBeans();
    require(!collectors.isEmpty(), "No GC counters are available for this JVM");
    for (var collector : collectors) {
      long count = collector.getCollectionCount();
      long time = collector.getCollectionTime();
      require(
          count >= 0L && time >= 0L,
          "GC counters are unavailable; do not substitute zero observations");
      gcCount += count;
      gcTime += time;
    }
    log.samples.row(
        now,
        log.phase(),
        submitted,
        acknowledged,
        observation.count(),
        Math.max(0L, now - observation.observedAt()),
        progress.queries.get(),
        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
        gcCount,
        gcTime,
        workers.active(),
        gate.entered(),
        wait);
  }

  private static String producerWait(Thread producer) {
    if (producer == null) return "not-started";
    boolean queuePut = false;
    boolean submit = false;
    boolean acknowledgement = false;
    for (StackTraceElement frame : producer.getStackTrace()) {
      queuePut |=
          (frame.getClassName().endsWith("BlockingQueue")
              && Set.of("put", "offer").contains(frame.getMethodName()));
      submit |=
          frame
                  .getClassName()
                  .equals("org.hibernate.search.engine.backend.orchestration.spi.BatchingExecutor")
              && frame.getMethodName().equals("submit");
      acknowledgement |=
          frame.getClassName().equals("java.util.concurrent.CompletableFuture")
              && Set.of("waitingGet", "timedGet").contains(frame.getMethodName());
    }
    if (queuePut && submit) return "queue";
    if (acknowledgement) return "completion";
    return producer.getState().name().toLowerCase(java.util.Locale.ROOT);
  }

  private static final class Progress {
    final AtomicInteger submitted;
    final AtomicInteger acknowledged;
    final AtomicReference<VisibilityObservation> visibility;
    final AtomicInteger queries = new AtomicInteger();
    final Set<String> seedIds;

    Progress(int seed, long observed, Set<String> seedIds) {
      this.seedIds = seedIds;
      submitted = new AtomicInteger(seed);
      acknowledged = new AtomicInteger(seed);
      visibility = new AtomicReference<>(new VisibilityObservation(seed, observed));
    }
  }

  private record VisibilityObservation(int count, long observedAt) {}

  private static void awaitVisible(
      Fixture fixture,
      int expected,
      Duration timeout,
      RuntimeBenchmarkFailureHandler handler,
      SearchRuntimeDiagnosticLog log)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (fixture.visibleCount() != expected) {
      handler.assertNoFailure();
      log.assertHealthy();
      require(System.nanoTime() < deadline, "Visibility did not drain before diagnostic timeout");
      Thread.sleep(10L);
    }
  }

  private static void verifyMembership(
      Fixture fixture, List<ObjectId> history, Properties completion, boolean reopened)
      throws Exception {
    Set<String> expected = new HashSet<>(history.stream().map(ObjectId::name).toList());
    Set<String> sql;
    try (var session = fixture.provider.getSessionFactory().openSession()) {
      var rows =
          session
              .createSelectionQuery(
                  "select objectId from GitCommitIndex where repositoryName = :name", String.class)
              .setParameter("name", NAME)
              .getResultList();
      sql = Set.copyOf(rows);
      require(
          rows.size() == expected.size() && sql.equals(expected),
          "SQL membership differs from Git fixture");
    }
    var hits = fixture.search.searchCommitTextSummaries(NAME, "diagnostic", history.size());
    Set<String> indexed = new HashSet<>(hits.stream().map(hit -> hit.objectId()).toList());
    require(
        hits.size() == expected.size() && indexed.equals(expected),
        "Lucene membership differs from Git fixture");
    var head = fixture.repository.exactRef(REF).getObjectId();
    require(head.equals(history.getLast()), "Published Git head differs");
    Set<String> reachable = new HashSet<>();
    try (var walk = new RevWalk(fixture.repository)) {
      walk.markStart(walk.parseCommit(head));
      for (var commit : walk) {
        require(reachable.size() < history.size(), "Git walk exceeded the bounded fixture");
        reachable.add(commit.name());
      }
    }
    require(reachable.equals(expected), "Git reachable history differs from projection");
    String prefix = reopened ? "reopened." : "";
    completion.setProperty(prefix + "sql.commits", Integer.toString(sql.size()));
    completion.setProperty(prefix + "visible.commits", Integer.toString(indexed.size()));
    completion.setProperty("membership." + (reopened ? "reopen" : "git") + ".verified", "true");
  }

  private static List<ObjectId> createHistory(HibernateRepository repository, Options options)
      throws Exception {
    List<ObjectId> result = new ArrayList<>(options.totalCommits());
    ObjectId parent = null;
    var objectChecker = new ObjectChecker();
    try (var inserter = repository.newObjectInserter()) {
      for (int index = 0; index < options.totalCommits(); index++) {
        String marker = index < options.seedCommits() ? "steadyneedle" : "streamneedle";
        String text =
            "diagnostic "
                + marker
                + " revision "
                + index
                + "\n"
                + "boundedruntimecontent ".repeat(200);
        var tree = new TreeFormatter();
        tree.append(
            "diagnostic.txt",
            FileMode.REGULAR_FILE,
            inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8)));
        byte[] treeBytes = tree.toByteArray();
        objectChecker.check(Constants.OBJ_TREE, treeBytes);
        var commit = new CommitBuilder();
        commit.setTreeId(inserter.insert(Constants.OBJ_TREE, treeBytes));
        if (parent != null) commit.setParentId(parent);
        var who =
            new PersonIdent(
                "Runtime diagnostic",
                "runtime@example.invalid",
                Instant.ofEpochSecond(1_710_000_000L + index),
                ZoneOffset.UTC);
        commit.setAuthor(who);
        commit.setCommitter(who);
        commit.setMessage("diagnostic " + marker + " commit " + index);
        parent = inserter.insert(commit);
        result.add(parent);
      }
      inserter.flush();
    }
    return List.copyOf(result);
  }

  private static void advanceRef(HibernateRepository repository, ObjectId target) throws Exception {
    var previous = repository.exactRef(REF);
    var update = repository.updateRef(REF);
    update.setExpectedOldObjectId(previous == null ? ObjectId.zeroId() : previous.getObjectId());
    update.setNewObjectId(target);
    update.disableRefLog();
    var result = update.update();
    require(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD,
        "Could not advance diagnostic Git ref: " + result);
  }

  private static Fixture open(
      Properties connection,
      Path index,
      Options options,
      boolean create,
      SearchRuntimeOutputGate gate,
      RuntimeBenchmarkFailureHandler handler)
      throws Exception {
    Properties properties = new Properties();
    properties.putAll(connection);
    properties.setProperty("hibernate.hbm2ddl.auto", create ? "create" : "validate");
    properties.setProperty("hibernate.connection.pool_size", "8");
    properties.setProperty("hibernate.show_sql", "false");
    properties.setProperty("hibernate.search.backend.type", "lucene");
    properties.setProperty(
        "hibernate.search.schema_management.strategy", create ? "create" : "validate");
    properties.setProperty(
        SearchIndexingProfile.PROFILE_PROPERTY, SearchIndexingProfile.CONTENT.id());
    properties.put(
        "hibernate.search.background_failure_handler", BeanReference.ofInstance(handler));
    properties.put(
        "hibernate.search.backend.directory.type",
        BeanReference.ofInstance(directory(index, gate)));
    SearchRuntimeScenario.fromId(options.scenario()).apply(properties);
    properties.setProperty(
        CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, Integer.toString(options.batchSize()));
    properties.setProperty("hibernate.jdbc.batch_size", Integer.toString(options.batchSize()));
    properties.setProperty("hibernate.search.backend.indexing.queue_count", "1");
    properties.setProperty("hibernate.search.backend.indexing.queue_size", "8");
    properties.setProperty("hibernate.search.backend.thread_pool.size", "1");
    properties.setProperty("hibernate.search.backend.io.writer.ram_buffer_size", "16");
    properties.setProperty(
        "hibernate.search.backend.io.writer.max_buffered_docs",
        Integer.toString(options.maxBufferedDocs()));
    if (options.traced()) {
      properties.setProperty("hibernate.search.backend.io.writer.infostream", "true");
    } else {
      // Hibernate Search 8.4 maps an explicit false to null, rejected by Lucene 9.12.
      // Absence preserves the default InfoStream.NO_OUTPUT timing path.
      properties.remove("hibernate.search.backend.io.writer.infostream");
    }
    if ("org.postgresql.Driver"
        .equals(properties.getProperty("hibernate.connection.driver_class"))) {
      properties.setProperty("hibernate.connection.socketTimeout", "15");
      properties.setProperty("hibernate.connection.connectTimeout", "15");
    }
    BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
    var provider =
        new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
    HibernateRepository repository = null;
    try {
      repository = HibernateRepository.create(provider.getSessionFactory(), NAME);
      if (create) repository.create(true);
      return new Fixture(provider, repository);
    } catch (Throwable failure) {
      try (provider) {
        if (repository != null) repository.close();
      } catch (Throwable close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  private static DirectoryProvider directory(Path root, SearchRuntimeOutputGate gate) {
    return context ->
        new DirectoryHolder() {
          private Directory directory;

          @Override
          public void start() throws IOException {
            Path path = root.resolve(context.indexName());
            Files.createDirectories(path);
            directory =
                new FilterDirectory(FSDirectory.open(path)) {
                  @Override
                  public IndexOutput createOutput(String name, IOContext context)
                      throws IOException {
                    gate.beforeOutput();
                    return super.createOutput(name, context);
                  }

                  @Override
                  public IndexOutput createTempOutput(
                      String prefix, String suffix, IOContext context) throws IOException {
                    gate.beforeOutput();
                    return super.createTempOutput(prefix, suffix, context);
                  }
                };
          }

          @Override
          public Directory get() {
            return directory;
          }

          @Override
          public void close() throws IOException {
            if (directory != null) directory.close();
          }
        };
  }

  private static final class Fixture implements AutoCloseable {
    final HibernateSessionFactoryProvider provider;
    final HibernateRepository repository;
    final CommitIndexer indexer;
    final GitHistorySearchService search;
    IntSupplier activeWorkers = () -> 0;

    Fixture(HibernateSessionFactoryProvider provider, HibernateRepository repository) {
      this.provider = provider;
      this.repository = repository;
      indexer = new CommitIndexer(provider.getSessionFactory(), NAME);
      search = new GitHistorySearchService(provider.getSessionFactory());
    }

    int visibleCount() {
      try (var session = provider.getSessionFactory().openSession()) {
        return Math.toIntExact(
            Search.session(session)
                .search(GitCommitIndex.class)
                .where(f -> f.match().field("repositoryName").matching(NAME))
                .fetchTotalHitCount());
      }
    }

    @Override
    public void close() {
      require(
          activeWorkers.getAsInt() == 0,
          "Refusing to close storage while a diagnostic worker still uses it");
      try (provider;
          repository) {
        /* Ownership order: repository, then factory. */
      }
    }
  }

  private static Properties metadata(Options options) throws IOException {
    Properties metadata = new Properties();
    metadata.setProperty("schema.version", "1");
    metadata.setProperty("source.commit", System.getProperty("benchmark.commit", "local-test"));
    metadata.setProperty("source.tree", System.getProperty("benchmark.tree", "local-test"));
    metadata.setProperty("scenario", options.scenario());
    metadata.setProperty("resources", options.resources());
    metadata.setProperty("pass", options.traced() ? "trace" : "timing");
    metadata.setProperty("trace.enabled", Boolean.toString(options.traced()));
    metadata.setProperty("seed.commits", Integer.toString(options.seedCommits()));
    metadata.setProperty("batch.commits", Integer.toString(options.batchSize()));
    metadata.setProperty("sustained.batches", Integer.toString(options.sustainedBatches()));
    metadata.setProperty("overload.batches", Integer.toString(options.overloadBatches()));
    metadata.setProperty("period.millis", Integer.toString(options.periodMillis()));
    metadata.setProperty("gate.requested.hold.millis", Integer.toString(options.gateHoldMillis()));
    metadata.setProperty("timeout.seconds", Integer.toString(options.timeoutSeconds()));
    metadata.setProperty("queue.count", "1");
    metadata.setProperty("queue.size", "8");
    metadata.setProperty("writer.ram.buffer.mib", "16");
    metadata.setProperty("writer.max.buffered.docs", Integer.toString(options.maxBufferedDocs()));
    metadata.setProperty("backend.threads", "1");
    metadata.setProperty("heap.max.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
    metadata.setProperty(
        "available.processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
    metadata.setProperty("java.runtime.version", System.getProperty("java.runtime.version"));
    metadata.setProperty(
        "hibernate.search.version", org.hibernate.search.engine.Version.versionString());
    metadata.setProperty("lucene.version", org.apache.lucene.util.Version.LATEST.toString());
    metadata.setProperty("os.name", System.getProperty("os.name"));
    metadata.setProperty("os.arch", System.getProperty("os.arch"));
    metadata.setProperty("started.at", Instant.now().toString());
    metadata.setProperty("runner", System.getenv().getOrDefault("RUNNER_NAME", "local"));
    Path process = Path.of("/proc/self/status");
    if (Files.isReadable(process)) {
      try (var lines = Files.lines(process)) {
        metadata.setProperty(
            "cpu.affinity",
            lines
                .filter(line -> line.startsWith("Cpus_allowed_list:"))
                .map(line -> line.substring("Cpus_allowed_list:".length()).strip())
                .findFirst()
                .orElse("unknown"));
      }
    }
    return metadata;
  }

  private static void captureDatabaseMetadata(Fixture fixture, Properties metadata) {
    try (var session = fixture.provider.getSessionFactory().openSession()) {
      session.doWork(
          connection -> {
            var database = connection.getMetaData();
            metadata.setProperty("database.product", database.getDatabaseProductName());
            metadata.setProperty("database.version", database.getDatabaseProductVersion());
            metadata.setProperty("jdbc.driver.version", database.getDriverVersion());
            if ("PostgreSQL".equals(database.getDatabaseProductName())) {
              for (String setting : List.of("fsync", "synchronous_commit", "full_page_writes")) {
                try (var statement = connection.createStatement();
                    var values = statement.executeQuery("SHOW " + setting)) {
                  require(values.next(), "Missing PostgreSQL durability setting: " + setting);
                  String actual = values.getString(1);
                  metadata.setProperty("postgresql." + setting, actual);
                  require(
                      "on".equals(actual),
                      "PostgreSQL durability setting differs: " + setting + "=" + actual);
                  require(!values.next(), "Duplicate PostgreSQL durability setting: " + setting);
                }
              }
            }
          });
    }
  }

  private static void writeProperties(Path path, Properties properties) throws IOException {
    try (var writer = Files.newBufferedWriter(path)) {
      properties.store(writer, "Search runtime diagnostic");
    }
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
