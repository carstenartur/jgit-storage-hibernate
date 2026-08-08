/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * Measures commit-projection indexing, rebuild and representative Search query paths.
 *
 * <p>Every invocation receives a fresh PostgreSQL schema, logical Git repository and
 * local-filesystem Lucene directory outside measured time. Query benchmarks receive a fully built
 * projection. The incremental-indexing benchmark starts with no projection, while rebuild starts
 * with one complete generation so purge and recreation are both measured. Deterministic result
 * checksums prevent the JVM from discarding query work and make divergent query semantics visible
 * in raw results.
 *
 * <p>The fixture deliberately models repeated small edits to one approximately 8 KiB text file.
 * Only one fixed-width marker line changes per commit. This makes CONTENT index the complete current
 * file repeatedly while DIFF_HUNKS can retain only the changed line, which is the workload the
 * profile is intended to optimize.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class HibernateSearchPerformanceBenchmark {

  private static final AtomicInteger INVOCATION = new AtomicInteger();
  private static final String NEEDLE = "needle";
  private static final String CONTENT_NEEDLE = "payloadneedle";
  private static final String BENCHMARK_PATH = "services/payments/fraud/rules.txt";
  private static final String PATH_FRAGMENT = "payments/fraud";
  private static final String PATH_TERMS = "services payments fraud";
  private static final int CONTENT_BYTES = 8 * 1024;
  private static final int STABLE_LINE_PAYLOAD = 48;

  @Param({"100"})
  public int commitCount;

  @Param({"50"})
  public int queryLimit;

  @Param({"content-v1"})
  public String indexProfile;

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private GitHistorySearchService searchService;
  private Statistics statistics;
  private String repositoryName;
  private ObjectId headCommit;
  private Path indexRoot;
  private String operation;
  private long prebuiltLuceneBytes;
  private long prebuiltSqlBytes;
  private long prebuiltSegmentCount;

  @Setup(Level.Invocation)
  public void setupInvocation(BenchmarkParams params) throws Exception {
    operation = params.getBenchmark().substring(params.getBenchmark().lastIndexOf('.') + 1);
    repositoryName =
        "jmh-search-"
            + operation
            + "-"
            + indexProfile.replace('-', '_')
            + "-"
            + INVOCATION.incrementAndGet()
            + "-"
            + Long.toHexString(System.nanoTime());
    indexRoot = Files.createTempDirectory("jgit-storage-search-jmh-");
    provider = new HibernateSessionFactoryProvider(properties(), SearchEntities.annotatedClasses());
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    headCommit = createHistory();
    searchService = new GitHistorySearchService(provider.getSessionFactory());

    if (!"incrementalIndexing".equals(operation)) {
      new CommitProjectionRebuilder(provider.getSessionFactory())
          .rebuild(repository, new RepositoryName(repositoryName));
      prebuiltLuceneBytes = directoryBytes(indexRoot);
      prebuiltSqlBytes = postgresProjectionBytes();
      prebuiltSegmentCount = luceneSegmentCount(indexRoot);
    } else {
      prebuiltLuceneBytes = 0L;
      prebuiltSqlBytes = 0L;
      prebuiltSegmentCount = 0L;
    }
    statistics = provider.getSessionFactory().getStatistics();
    statistics.clear();
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() throws Exception {
    try {
      if (repository != null) {
        repository.close();
        repository = null;
      }
      if (provider != null) {
        provider.close();
        provider = null;
      }
    } finally {
      deleteRecursively(indexRoot);
      indexRoot = null;
      statistics = null;
      searchService = null;
      headCommit = null;
    }
  }

  /** Index a previously unprojected reachable history through bounded CommitIndexer batches. */
  @Benchmark
  public int incrementalIndexing(SearchCounters counters) throws Exception {
    int indexed =
        new CommitIndexer(provider.getSessionFactory(), repositoryName)
            .indexCommitsFrom(repository, headCommit, -1);
    counters.capture(statistics, indexed, 0L, 0L, 0L);
    if (indexed != commitCount) {
      throw new IllegalStateException(
          "Expected to index " + commitCount + " commits, indexed " + indexed);
    }
    return indexed;
  }

  /** Purge the complete derived projection and rebuild it from authoritative Git history. */
  @Benchmark
  public int projectionRebuild(SearchCounters counters) throws Exception {
    var result =
        new CommitProjectionRebuilder(provider.getSessionFactory())
            .rebuild(repository, new RepositoryName(repositoryName));
    counters.capture(
        statistics,
        result.indexedCommits(),
        prebuiltLuceneBytes,
        prebuiltSqlBytes,
        prebuiltSegmentCount);
    if (result.indexedCommits() != commitCount || result.removedProjections() != commitCount) {
      throw new IllegalStateException(
          "Unexpected rebuild result: indexed="
              + result.indexedCommits()
              + ", removed="
              + result.removedProjections());
    }
    return result.indexedCommits();
  }

  /** Full-text result list through managed-entity hydration. */
  @Benchmark
  public int fullTextEntityHits(SearchCounters counters) {
    List<GitCommitIndex> hits = searchService.searchCommitText(repositoryName, NEEDLE, queryLimit);
    counters.capture(
        statistics, hits.size(), prebuiltLuceneBytes, prebuiltSqlBytes, prebuiltSegmentCount);
    return entityChecksum(hits);
  }

  /** Full-text result list projected directly from Lucene stored fields. */
  @Benchmark
  public int fullTextSummaryHits(SearchCounters counters) {
    List<CommitSearchHit> hits =
        searchService.searchCommitTextSummaries(repositoryName, NEEDLE, queryLimit);
    counters.capture(
        statistics, hits.size(), prebuiltLuceneBytes, prebuiltSqlBytes, prebuiltSegmentCount);
    return summaryChecksum(hits);
  }

  /** Content-only recall probe; metadata/path profiles intentionally return no hits. */
  @Benchmark
  public int contentOnlySummaryHits(SearchCounters counters) {
    List<CommitSearchHit> hits =
        searchService.searchCommitTextSummaries(repositoryName, CONTENT_NEEDLE, queryLimit);
    counters.capture(
        statistics, hits.size(), prebuiltLuceneBytes, prebuiltSqlBytes, prebuiltSegmentCount);
    return summaryChecksum(hits);
  }

  /** Backward-compatible literal path-fragment query through SQL/HQL. */
  @Benchmark
  public int pathLiteralSql(SearchCounters counters) {
    List<CommitSearchHit> hits =
        searchService.findChangeSummaries(
            CommitHistoryQuery.forRepository(repositoryName)
                .touchingPath(PATH_FRAGMENT)
                .limit(queryLimit)
                .build());
    counters.capture(
        statistics, hits.size(), prebuiltLuceneBytes, prebuiltSqlBytes, prebuiltSegmentCount);
    return summaryChecksum(hits);
  }

  /** Explicit analyzed path-term query through Lucene. */
  @Benchmark
  public int pathTermsLucene(SearchCounters counters) {
    List<CommitSearchHit> hits =
        searchService.findChangeSummaries(
            CommitHistoryQuery.forRepository(repositoryName)
                .touchingPathTerms(PATH_TERMS)
                .limit(queryLimit)
                .build());
    counters.capture(
        statistics, hits.size(), prebuiltLuceneBytes, prebuiltSqlBytes, prebuiltSegmentCount);
    return summaryChecksum(hits);
  }

  private ObjectId createHistory() throws Exception {
    ObjectId parent = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 0; index < commitCount; index++) {
        byte[] content = deterministicContent(index);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content);
        TreeFormatter tree = new TreeFormatter();
        tree.append(BENCHMARK_PATH, FileMode.REGULAR_FILE, blob);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(inserter.insert(tree));
        if (parent != null) {
          commit.setParentId(parent);
        }
        PersonIdent identity =
            new PersonIdent(
                "Search benchmark",
                "search-benchmark@example.invalid",
                Instant.ofEpochSecond(1_700_000_000L + index),
                ZoneOffset.UTC);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage(
            (index % 5 == 0 ? "Needle " : "Routine ") + "projection commit " + index);
        parent = inserter.insert(commit);
      }
      inserter.flush();
    }

    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(parent);
    update.disableRefLog();
    if (update.update() != RefUpdate.Result.NEW) {
      throw new IllegalStateException("Could not publish deterministic Search benchmark history");
    }
    return parent;
  }

  /**
   * Create an ASCII file whose size and all lines except the first remain identical between commits.
   */
  private static byte[] deterministicContent(int index) {
    String marker = index % 5 == 0 ? CONTENT_NEEDLE : "ordinary";
    StringBuilder content = new StringBuilder(CONTENT_BYTES + 128);
    content.append(String.format("%-13s revision %06d\n", marker, index));

    int line = 0;
    while (content.length() < CONTENT_BYTES) {
      content.append(String.format("stable-line-%04d: ", line));
      int value = line ^ 0x53454152;
      for (int column = 0; column < STABLE_LINE_PAYLOAD; column++) {
        value = value * 1664525 + 1013904223;
        content.append((char) ('a' + Math.floorMod(value, 26)));
      }
      content.append('\n');
      line++;
    }
    content.setLength(CONTENT_BYTES);
    return content.toString().getBytes(StandardCharsets.UTF_8);
  }

  private Properties properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY));
    properties.put(
        "hibernate.connection.username",
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY));
    properties.put(
        "hibernate.connection.password",
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(
        "hibernate.jdbc.batch_size", Integer.toString(CommitIndexer.DEFAULT_INDEX_BATCH_SIZE));
    properties.put("hibernate.order_inserts", "true");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-filesystem");
    properties.put("hibernate.search.backend.directory.root", indexRoot.toString());
    properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
    properties.put(SearchIndexingProfile.PROFILE_PROPERTY, indexProfile);
    properties.put(
        CommitIndexer.INDEX_BATCH_SIZE_PROPERTY,
        Integer.toString(CommitIndexer.DEFAULT_INDEX_BATCH_SIZE));
    properties.put(
        CommitProjectionRebuilder.PURGE_BATCH_SIZE_PROPERTY,
        Integer.toString(CommitProjectionRebuilder.DEFAULT_PURGE_BATCH_SIZE));
    return properties;
  }

  private long postgresProjectionBytes() {
    try (Session session = provider.getSessionFactory().openSession()) {
      Number value =
          (Number)
              session
                  .createNativeQuery("select pg_total_relation_size('git_commit_index')")
                  .getSingleResult();
      return value.longValue();
    }
  }

  private static long directoryBytes(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return 0L;
    }
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(HibernateSearchPerformanceBenchmark::size)
          .sum();
    }
  }

  private static long luceneSegmentCount(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return 0L;
    }
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("_") && name.endsWith(".si"))
          .count();
    }
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not measure Lucene file " + path, exception);
    }
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing Search benchmark system property " + name);
    }
    return value;
  }

  private static int entityChecksum(List<GitCommitIndex> hits) {
    int checksum = hits.size();
    for (GitCommitIndex hit : hits) {
      checksum = 31 * checksum + hit.getObjectId().hashCode();
      String changedText = hit.getChangedText();
      checksum = 31 * checksum + (changedText == null ? 0 : changedText.length());
    }
    return checksum;
  }

  private static int summaryChecksum(List<CommitSearchHit> hits) {
    int checksum = hits.size();
    for (CommitSearchHit hit : hits) {
      checksum = 31 * checksum + hit.objectId().hashCode();
      checksum = 31 * checksum + hit.shortMessage().hashCode();
    }
    return checksum;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  /** ORM/database and footprint counters attributed to one measured Search operation. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class SearchCounters {
    public long resultCount;
    public long queryExecutions;
    public long entityLoads;
    public long preparedStatements;
    public long transactions;
    public long successfulTransactions;
    public long flushes;
    public long luceneBytes;
    public long sqlProjectionBytes;
    public long segmentCount;

    @Setup(Level.Invocation)
    public void reset() {
      resultCount = 0;
      queryExecutions = 0;
      entityLoads = 0;
      preparedStatements = 0;
      transactions = 0;
      successfulTransactions = 0;
      flushes = 0;
      luceneBytes = 0;
      sqlProjectionBytes = 0;
      segmentCount = 0;
    }

    private void capture(
        Statistics statistics,
        long results,
        long luceneBytes,
        long sqlProjectionBytes,
        long segmentCount) {
      resultCount = results;
      queryExecutions = statistics.getQueryExecutionCount();
      entityLoads = statistics.getEntityLoadCount();
      preparedStatements = statistics.getPrepareStatementCount();
      transactions = statistics.getTransactionCount();
      successfulTransactions = statistics.getSuccessfulTransactionCount();
      flushes = statistics.getFlushCount();
      this.luceneBytes = luceneBytes;
      this.sqlProjectionBytes = sqlProjectionBytes;
      this.segmentCount = segmentCount;
    }
  }
}
