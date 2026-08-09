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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
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

/**
 * Compares equivalent application-history questions across ordinary JGit and an indexed projection.
 *
 * <p>Both on-demand variants reuse one {@link ObjectReader} and one {@link RevWalk}, apply cheap
 * author/time/message predicates before tree inspection, stop once a lower time boundary is crossed,
 * and restrict tree work to the exact requested path. The deterministic fixture uses a normal
 * nested Git tree generated through {@link DirCache#writeTree(ObjectInserter)}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@Threads(1)
public class CanonicalHistoryQueryCrossoverBenchmark {

  static final String FILESYSTEM_JGIT = "filesystem-jgit";
  static final String HIBERNATE_JGIT = "hibernate-jgit";
  static final String INDEXED_PROJECTION = "indexed-projection";

  static final String AUTHOR_TIME = "author-time";
  static final String PATH_TIME = "path-time";
  static final String MESSAGE_TEXT = "message-text";
  static final String PATH_CONTENT = "path-content";
  static final String COMPOUND = "compound";

  private static final AtomicInteger INVOCATION = new AtomicInteger();
  private static final Instant BASE_TIME = Instant.ofEpochSecond(1_700_000_000L);
  private static final int SPECIAL_MODULUS = 251;
  private static final int CONTENT_BYTES = 512;
  private static final String ALICE_EMAIL = "alice@example.invalid";
  private static final String MESSAGE_NEEDLE = "incidentmarker";
  private static final String CONTENT_NEEDLE = "policyneedle";
  private static final String TARGET_PATH = "services/payments/fraud/rules.txt";
  private static final String[] PATHS = {
    "README.md",
    "docs/operations.txt",
    "services/payments/core.txt",
    TARGET_PATH
  };
  private static final int TARGET_PATH_INDEX = PATHS.length - 1;

  @Benchmark
  public int query(QueryState state, HistoryCounters counters) throws Exception {
    QueryResult result = state.execute();
    counters.capture(state, result);
    return checksum(result.objectIds());
  }

  @Benchmark
  public int projectionBuild(ProjectionBuildState state, HistoryCounters counters) throws Exception {
    var result =
        new CommitProjectionRebuilder(state.provider.getSessionFactory())
            .rebuild(state.repository, new RepositoryName(state.repositoryName));
    if (result.indexedCommits() != state.commitCount) {
      throw new IllegalStateException(
          "Expected " + state.commitCount + " indexed commits, got " + result.indexedCommits());
    }
    counters.captureProjectionBuild(state, result.indexedCommits());
    return result.indexedCommits();
  }

  @State(Scope.Thread)
  public static class QueryState {

    @Param({FILESYSTEM_JGIT, HIBERNATE_JGIT, INDEXED_PROJECTION})
    public String engine;

    @Param({AUTHOR_TIME, PATH_TIME, MESSAGE_TEXT, PATH_CONTENT, COMPOUND})
    public String queryKind;

    @Param({"1000"})
    public int commitCount;

    @Param({"500"})
    public int queryLimit;

    private HibernateSessionFactoryProvider provider;
    private Repository repository;
    private Path repositoryDirectory;
    private Path indexRoot;
    private String repositoryName;
    private ObjectId headCommit;
    private GitHistorySearchService searchService;
    private Statistics statistics;
    private Instant from;
    private Instant to;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
      validateBounds(commitCount, queryLimit);
      int fixture = INVOCATION.incrementAndGet();
      repositoryName =
          "jmh-history-query-"
              + engine.replace('-', '_')
              + "-"
              + queryKind.replace('-', '_')
              + "-"
              + commitCount
              + "-"
              + fixture;
      from = BASE_TIME.plusSeconds(Math.max(1, commitCount / 10));
      to = BASE_TIME.plusSeconds(commitCount - 1L - Math.max(1, commitCount / 10));

      if (FILESYSTEM_JGIT.equals(engine)) {
        repositoryDirectory = Files.createTempDirectory("jgit-history-query-filesystem-");
        repository =
            new FileRepositoryBuilder()
                .setGitDir(repositoryDirectory.toFile())
                .setBare()
                .build();
      } else {
        indexRoot = Files.createTempDirectory("jgit-history-query-search-");
        boolean searchEnabled = INDEXED_PROJECTION.equals(engine);
        provider =
            searchEnabled
                ? new HibernateSessionFactoryProvider(
                    databaseProperties(indexRoot, true), SearchEntities.annotatedClasses())
                : new HibernateSessionFactoryProvider(databaseProperties(indexRoot, false));
        repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
        statistics = provider.getSessionFactory().getStatistics();
      }

      repository.create(true);
      headCommit = createHistory(repository, commitCount);

      if (INDEXED_PROJECTION.equals(engine)) {
        new CommitProjectionRebuilder(provider.getSessionFactory())
            .rebuild(repository, new RepositoryName(repositoryName));
        searchService = new GitHistorySearchService(provider.getSessionFactory());
        verifyIndexedSemantics();
      }
      if (statistics != null) {
        statistics.clear();
      }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
      if (statistics != null) {
        statistics.clear();
      }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
      if (repository != null) {
        repository.close();
        repository = null;
      }
      if (provider != null) {
        provider.close();
        provider = null;
      }
      deleteRecursively(repositoryDirectory);
      deleteRecursively(indexRoot);
      repositoryDirectory = null;
      indexRoot = null;
    }

    private QueryResult execute() throws Exception {
      return INDEXED_PROJECTION.equals(engine) ? executeIndexed() : executeOnDemand();
    }

    private QueryResult executeIndexed() {
      List<CommitSearchHit> hits = searchService.findChangeSummaries(indexedQuery());
      List<String> objectIds = hits.stream().map(CommitSearchHit::objectId).sorted().toList();
      return new QueryResult(objectIds, 0L, 0L, 0L, 0L);
    }

    private QueryResult executeOnDemand() throws Exception {
      return onDemandQuery(repository, headCommit, queryKind, from, to, queryLimit);
    }

    private CommitHistoryQuery indexedQuery() {
      CommitHistoryQuery.Builder query = CommitHistoryQuery.forRepository(repositoryName);
      switch (queryKind) {
        case AUTHOR_TIME -> query.authoredBy(ALICE_EMAIL).committedBetween(from, to);
        case PATH_TIME -> query.touchingExactPath(TARGET_PATH).committedBetween(from, to);
        case MESSAGE_TEXT -> query.matchingText(MESSAGE_NEEDLE);
        case PATH_CONTENT ->
            query.matchingText(CONTENT_NEEDLE).touchingExactPath(TARGET_PATH);
        case COMPOUND ->
            query
                .matchingText(CONTENT_NEEDLE)
                .authoredBy(ALICE_EMAIL)
                .touchingExactPath(TARGET_PATH)
                .committedBetween(from, to);
        default -> throw new IllegalArgumentException("Unsupported query kind " + queryKind);
      }
      return query.limit(queryLimit).build();
    }

    private void verifyIndexedSemantics() throws Exception {
      QueryResult onDemand = executeOnDemand();
      QueryResult indexed = executeIndexed();
      if (!onDemand.objectIds().equals(indexed.objectIds())) {
        throw new IllegalStateException(
            "Indexed query semantics diverged from JGit for "
                + queryKind
                + ": on-demand="
                + onDemand.objectIds()
                + ", indexed="
                + indexed.objectIds());
      }
    }
  }

  @State(Scope.Thread)
  public static class ProjectionBuildState {

    @Param({"1000"})
    public int commitCount;

    private HibernateSessionFactoryProvider provider;
    private HibernateRepository repository;
    private Path indexRoot;
    private String repositoryName;
    private Statistics statistics;

    @Setup(Level.Invocation)
    public void setupInvocation() throws Exception {
      int fixture = INVOCATION.incrementAndGet();
      repositoryName = "jmh-history-build-" + commitCount + "-" + fixture;
      indexRoot = Files.createTempDirectory("jgit-history-build-search-");
      provider =
          new HibernateSessionFactoryProvider(
              databaseProperties(indexRoot, true), SearchEntities.annotatedClasses());
      repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
      repository.create(true);
      createHistory(repository, commitCount);
      statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws IOException {
      if (repository != null) {
        repository.close();
        repository = null;
      }
      if (provider != null) {
        provider.close();
        provider = null;
      }
      deleteRecursively(indexRoot);
      indexRoot = null;
      statistics = null;
    }
  }

  private static QueryResult onDemandQuery(
      Repository repository,
      ObjectId headCommit,
      String queryKind,
      Instant from,
      Instant to,
      int queryLimit)
      throws Exception {
    List<String> hits = new ArrayList<>();
    long commitsVisited = 0L;
    long treeInspections = 0L;
    long blobsRead = 0L;
    long blobBytes = 0L;

    boolean needsAuthor = AUTHOR_TIME.equals(queryKind) || COMPOUND.equals(queryKind);
    boolean needsTime =
        AUTHOR_TIME.equals(queryKind) || PATH_TIME.equals(queryKind) || COMPOUND.equals(queryKind);
    boolean needsPath =
        PATH_TIME.equals(queryKind) || PATH_CONTENT.equals(queryKind) || COMPOUND.equals(queryKind);
    boolean needsContent = PATH_CONTENT.equals(queryKind) || COMPOUND.equals(queryKind);
    boolean needsMessage = MESSAGE_TEXT.equals(queryKind);

    try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
      walk.markStart(walk.parseCommit(headCommit));
      for (RevCommit commit : walk) {
        commitsVisited++;
        Instant commitTime = commit.getCommitterIdent().getWhenAsInstant();
        if (needsTime && commitTime.isAfter(to)) {
          continue;
        }
        if (needsTime && commitTime.isBefore(from)) {
          break;
        }
        if (needsAuthor
            && !ALICE_EMAIL.equalsIgnoreCase(commit.getAuthorIdent().getEmailAddress())) {
          continue;
        }
        if (needsMessage
            && !commit.getFullMessage().toLowerCase(Locale.ROOT).contains(MESSAGE_NEEDLE)) {
          continue;
        }

        if (needsPath) {
          PathMatchResult path = inspectTargetPath(reader, walk, commit, needsContent);
          treeInspections++;
          blobsRead += path.blobRead() ? 1L : 0L;
          blobBytes += path.blobBytes();
          if (!path.matches()) {
            continue;
          }
        }

        hits.add(commit.name());
        if (hits.size() >= queryLimit) {
          break;
        }
      }
    }
    hits.sort(String::compareTo);
    return new QueryResult(List.copyOf(hits), commitsVisited, treeInspections, blobsRead, blobBytes);
  }

  private static PathMatchResult inspectTargetPath(
      ObjectReader reader, RevWalk walk, RevCommit commit, boolean requireContent) throws Exception {
    try (TreeWalk treeWalk = new TreeWalk(reader)) {
      if (commit.getParentCount() == 0) {
        treeWalk.addTree(new EmptyTreeIterator());
      } else {
        treeWalk.addTree(walk.parseCommit(commit.getParent(0)).getTree());
      }
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      treeWalk.setFilter(
          AndTreeFilter.create(PathFilter.create(TARGET_PATH), TreeFilter.ANY_DIFF));
      if (!treeWalk.next()) {
        return new PathMatchResult(false, false, 0L);
      }
      if (!requireContent) {
        return new PathMatchResult(true, false, 0L);
      }
      if (FileMode.MISSING.equals(treeWalk.getFileMode(1))) {
        return new PathMatchResult(false, false, 0L);
      }
      ObjectLoader loader = reader.open(treeWalk.getObjectId(1), Constants.OBJ_BLOB);
      byte[] content = loader.getBytes();
      boolean matches =
          new String(content, StandardCharsets.UTF_8)
              .toLowerCase(Locale.ROOT)
              .contains(CONTENT_NEEDLE);
      return new PathMatchResult(matches, true, content.length);
    }
  }

  private static ObjectId createHistory(Repository repository, int commitCount) throws Exception {
    ObjectId[] blobs = new ObjectId[PATHS.length];
    ObjectId parent = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int pathIndex = 0; pathIndex < PATHS.length; pathIndex++) {
        blobs[pathIndex] =
            inserter.insert(
                Constants.OBJ_BLOB,
                deterministicContent(PATHS[pathIndex], -1, false));
      }

      for (int index = 0; index < commitCount; index++) {
        boolean special = index % SPECIAL_MODULUS == 0;
        int changedPath = special ? TARGET_PATH_INDEX : index % TARGET_PATH_INDEX;
        blobs[changedPath] =
            inserter.insert(
                Constants.OBJ_BLOB,
                deterministicContent(PATHS[changedPath], index, special));

        DirCache tree = DirCache.newInCore();
        DirCacheBuilder builder = tree.builder();
        for (int pathIndex = 0; pathIndex < PATHS.length; pathIndex++) {
          DirCacheEntry entry = new DirCacheEntry(PATHS[pathIndex]);
          entry.setFileMode(FileMode.REGULAR_FILE);
          entry.setObjectId(blobs[pathIndex]);
          builder.add(entry);
        }
        builder.finish();

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree.writeTree(inserter));
        if (parent != null) {
          commit.setParentId(parent);
        }
        Instant when = BASE_TIME.plusSeconds(index);
        PersonIdent author =
            special
                ? new PersonIdent("Alice", ALICE_EMAIL, when, ZoneOffset.UTC)
                : new PersonIdent(
                    "Developer " + Math.floorMod(index, 8),
                    "developer-" + Math.floorMod(index, 8) + "@example.invalid",
                    when,
                    ZoneOffset.UTC);
        PersonIdent committer =
            new PersonIdent("Integrator", "integrator@example.invalid", when, ZoneOffset.UTC);
        commit.setAuthor(author);
        commit.setCommitter(committer);
        commit.setMessage(
            (special ? MESSAGE_NEEDLE + " " : "routine ") + "history change " + index);
        parent = inserter.insert(commit);
      }
      inserter.flush();
    }

    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(parent);
    update.disableRefLog();
    if (update.update() != RefUpdate.Result.NEW) {
      throw new IllegalStateException("Could not publish deterministic history-query fixture");
    }
    return parent;
  }

  private static byte[] deterministicContent(String path, int index, boolean special) {
    String marker = special && TARGET_PATH.equals(path) ? CONTENT_NEEDLE : "ordinary";
    StringBuilder content = new StringBuilder(CONTENT_BYTES + 64);
    content
        .append(marker)
        .append(" path=")
        .append(path)
        .append(" revision=")
        .append(index)
        .append('\n');
    int line = 0;
    while (content.length() < CONTENT_BYTES) {
      content
          .append("stable-")
          .append(String.format(Locale.ROOT, "%03d", line))
          .append("-")
          .append(Integer.toHexString((path.hashCode() * 31) ^ (index * 17) ^ line))
          .append('\n');
      line++;
    }
    content.setLength(CONTENT_BYTES);
    return content.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static Properties databaseProperties(Path indexRoot, boolean searchEnabled) {
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
    properties.put("hibernate.connection.pool_size", "4");
    properties.put(
        "hibernate.jdbc.batch_size", Integer.toString(CommitIndexer.DEFAULT_INDEX_BATCH_SIZE));
    properties.put("hibernate.order_inserts", "true");

    if (searchEnabled) {
      properties.put("hibernate.search.backend.type", "lucene");
      properties.put("hibernate.search.backend.directory.type", "local-filesystem");
      properties.put("hibernate.search.backend.directory.root", indexRoot.toString());
      properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
      properties.put(SearchIndexingProfile.PROFILE_PROPERTY, "content-v1");
      properties.put(
          CommitIndexer.INDEX_BATCH_SIZE_PROPERTY,
          Integer.toString(CommitIndexer.DEFAULT_INDEX_BATCH_SIZE));
      properties.put(
          CommitProjectionRebuilder.PURGE_BATCH_SIZE_PROPERTY,
          Integer.toString(CommitProjectionRebuilder.DEFAULT_PURGE_BATCH_SIZE));
    } else {
      properties.put("hibernate.search.enabled", "false");
    }
    return properties;
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing history-query benchmark system property " + name);
    }
    return value;
  }

  private static int checksum(List<String> objectIds) {
    int checksum = objectIds.size();
    for (String objectId : objectIds) {
      checksum = 31 * checksum + objectId.hashCode();
    }
    return checksum;
  }

  private static void validateBounds(int commitCount, int queryLimit) {
    if (commitCount <= 0) {
      throw new IllegalArgumentException("commitCount must be positive");
    }
    if (queryLimit <= 0) {
      throw new IllegalArgumentException("queryLimit must be positive");
    }
    int maximumSpecialHits = (commitCount + SPECIAL_MODULUS - 1) / SPECIAL_MODULUS;
    if (maximumSpecialHits > queryLimit) {
      throw new IllegalArgumentException(
          "queryLimit="
              + queryLimit
              + " truncates the deterministic result set for commitCount="
              + commitCount
              + "; increase the limit so JGit and relevance-ranked Search compare complete sets");
    }
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

  private record PathMatchResult(boolean matches, boolean blobRead, long blobBytes) {}

  private record QueryResult(
      List<String> objectIds,
      long commitsVisited,
      long treeInspections,
      long blobsRead,
      long blobBytes) {}

  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class HistoryCounters {
    public long resultCount;
    public long commitsVisited;
    public long treeInspections;
    public long blobsRead;
    public long blobBytes;
    public long queryExecutions;
    public long preparedStatements;
    public long transactions;

    @Setup(Level.Invocation)
    public void reset() {
      resultCount = 0L;
      commitsVisited = 0L;
      treeInspections = 0L;
      blobsRead = 0L;
      blobBytes = 0L;
      queryExecutions = 0L;
      preparedStatements = 0L;
      transactions = 0L;
    }

    private void capture(QueryState state, QueryResult result) {
      resultCount = result.objectIds().size();
      commitsVisited = result.commitsVisited();
      treeInspections = result.treeInspections();
      blobsRead = result.blobsRead();
      blobBytes = result.blobBytes();
      captureStatistics(state.statistics);
    }

    private void captureProjectionBuild(ProjectionBuildState state, long results) {
      resultCount = results;
      captureStatistics(state.statistics);
    }

    private void captureStatistics(Statistics statistics) {
      if (statistics == null) {
        return;
      }
      queryExecutions = statistics.getQueryExecutionCount();
      preparedStatements = statistics.getPrepareStatementCount();
      transactions = statistics.getTransactionCount();
    }
  }
}
