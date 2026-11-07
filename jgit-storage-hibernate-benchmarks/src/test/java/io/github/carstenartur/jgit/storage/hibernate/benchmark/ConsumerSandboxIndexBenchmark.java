/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisConfiguration;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisEntities;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisResult;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaJdtAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaReferenceKind;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSourceSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaAnalysisRun;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;

/** Benchmark-owned persistence experiment for the incubating Java Analysis mappings. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ConsumerSandboxIndexBenchmark {
  private static final String REPOSITORY = "sandbox-projection";
  private static final JavaAnalysisConfiguration BINDINGS =
      JavaAnalysisConfiguration.java21BindingAware();
  private static final JavaAnalysisConfiguration PROJECT_BINDINGS =
      new JavaAnalysisConfiguration(
          BINDINGS.sourceLevel(), BINDINGS.bindingMode(), BINDINGS.classpathEntries(),
          List.of("src/main/java"), BINDINGS.encodings(), BINDINGS.modulepathEntries(),
          BINDINGS.includeRunningVmBootClasspath(), BINDINGS.analyzerVersion());

  @Benchmark
  public int sandboxPersistBodyEdit(Projection fixture, Counters counters) throws IOException {
    int changed = fixture.replaceChanged(fixture.analyzeChanged());
    counters.javaFilesAnalyzed = 1;
    counters.resultCount = changed;
    counters.capture(fixture);
    return changed;
  }

  @Benchmark
  public int sandboxRebuildSemanticIndex(Projection fixture, Counters counters) throws IOException {
    int changed = fixture.replaceSnapshot(fixture.head);
    counters.javaFilesAnalyzed = fixture.head.sources().size();
    counters.resultCount = changed;
    counters.capture(fixture);
    return changed;
  }

  @Benchmark
  public int sandboxPathSymbols(Projection fixture, Counters counters) throws IOException {
    int hits = fixture.pathSymbols().size();
    counters.resultCount = hits;
    counters.capture(fixture);
    return hits;
  }

  @Benchmark
  public int sandboxMethodReferences(Projection fixture, Counters counters) throws IOException {
    int hits = fixture.methodReferences().size();
    counters.resultCount = hits;
    counters.capture(fixture);
    return hits;
  }

  @State(Scope.Thread)
  public static class Projection {
    @Param({"10"})
    public int coordinate;

    private Path root;
    private Path indexRoot;
    private Repository repository;
    private Properties settings;
    private HibernateSessionFactoryProvider provider;
    private JavaProjectSnapshot before;
    private JavaProjectSnapshot head;
    private JavaProjectSnapshot current;
    private JavaSourceSnapshot changed;
    private List<SemanticFact> expectedHead;
    private List<SemanticFact> expectedCurrent;
    private boolean closed;

    @Setup(Level.Trial)
    public void setup() throws IOException {
      if (coordinate < 1 || coordinate > 1000) {
        throw new IllegalArgumentException("Sandbox persistence requires 1..1000 fixture classes");
      }
      root = Files.createTempDirectory("consumer-sandbox-index-");
      indexRoot = root.resolve("lucene");
      try {
        repository = new FileRepositoryBuilder().setGitDir(root.resolve("git").toFile())
            .setBare().build();
        repository.create(true);
        ObjectId tip = ConsumerBenchmarkFixtures.createSandboxHistory(repository, 17, coordinate, 5);
        try (var walk = new RevWalk(repository)) {
          before = snapshot(walk.parseCommit(tip).getParent(0));
        }
        head = snapshot(tip);
        var changes = head.sources().values().stream()
            .filter(source -> !source.blobId().equals(before.sources().get(source.path()).blobId()))
            .toList();
        if (!before.sources().keySet().equals(head.sources().keySet()) || changes.size() != 1) {
          throw new IllegalStateException("Expected one dependency-free body edit");
        }
        changed = changes.getFirst();
        var oracle = new JavaProjectAnalyzer().analyze(head, PROJECT_BINDINGS);
        if (!oracle.complete()) {
          throw new IllegalStateException("Full-head semantic oracle did not resolve the fixture");
        }
        expectedHead = facts(oracle.symbols(), oracle.references());
        settings = new Properties();
        settings.setProperty("hibernate.connection.url", "jdbc:hsqldb:file:" + root.resolve("database"));
        settings.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        settings.setProperty("hibernate.hbm2ddl.auto", "create-only");
        settings.setProperty("hibernate.generate_statistics", "true");
        settings.setProperty("hibernate.search.backend.type", "lucene");
        settings.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
        settings.setProperty("hibernate.search.backend.directory.root", indexRoot.toString());
        settings.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
        settings.setProperty("hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
        provider = new HibernateSessionFactoryProvider(settings, JavaAnalysisEntities.annotatedClasses());
      } catch (IOException | RuntimeException failure) {
        try {
          close();
        } catch (IOException | RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    @Setup(Level.Iteration)
    public void prepare(BenchmarkParams benchmark) {
      String operation = benchmark.getBenchmark();
      replaceSnapshot(operation.endsWith("sandboxPathSymbols") || operation.endsWith("sandboxMethodReferences")
          ? head : before);
      clearCounters();
    }

    void prepareBefore() {
      replaceSnapshot(before);
      clearCounters();
    }

    private void clearCounters() {
      sessionFactory().getStatistics().clear();
      JdbcBatchMetricsSessionEventListener.resetCurrentThread();
    }

    JavaAnalysisResult analyzeChanged() {
      var result = new JavaJdtAnalyzer().analyze(changed, BINDINGS);
      if (result.analysisRun().getErrorCount() != 0 || result.symbols().isEmpty()) {
        throw new IllegalStateException("Changed-file analysis did not resolve the fixture");
      }
      return result;
    }

    int replaceChanged(JavaAnalysisResult replacement) {
      replaceFile(changed.path(), replacement);
      current = head;
      expectedCurrent = expectedHead;
      return replacement.symbols().size() + replacement.references().size();
    }

    private int replaceSnapshot(JavaProjectSnapshot project) {
      var result = new JavaProjectAnalyzer().analyze(project, PROJECT_BINDINGS);
      if (!result.complete()) {
        throw new IllegalStateException("Snapshot analysis did not resolve the fixture");
      }
      // This controlled history retains the same paths across its final body edit.
      for (var entry : result.files().entrySet()) {
        replaceFile(entry.getKey(), entry.getValue());
      }
      current = project;
      expectedCurrent = project.commitId().equals(head.commitId())
          ? expectedHead : facts(result.symbols(), result.references());
      return result.symbols().size() + result.references().size();
    }

    private void replaceFile(String path, JavaAnalysisResult replacement) {
      try (var session = sessionFactory().openSession()) {
        var transaction = session.beginTransaction();
        try {
          Set<Long> runs = new HashSet<>();
          var oldSymbols = session.createSelectionQuery(
              "from JavaSymbolIndex where repositoryName = :repo and path = :path", JavaSymbolIndex.class)
              .setParameter("repo", REPOSITORY).setParameter("path", path).getResultList();
          var oldReferences = session.createSelectionQuery(
              "from JavaReferenceIndex where repositoryName = :repo and path = :path", JavaReferenceIndex.class)
              .setParameter("repo", REPOSITORY).setParameter("path", path).getResultList();
          oldReferences.forEach(row -> { runs.add(row.getAnalysisRunId()); session.remove(row); });
          oldSymbols.forEach(row -> { runs.add(row.getAnalysisRunId()); session.remove(row); });
          for (Long run : runs) {
            session.remove(session.find(JavaAnalysisRun.class, run));
          }
          session.persist(replacement.analysisRun());
          Long run = replacement.analysisRun().getId();
          for (var symbol : replacement.symbols()) {
            symbol.setAnalysisRunId(run);
            session.persist(symbol);
          }
          for (var reference : replacement.references()) {
            reference.setAnalysisRunId(run);
            session.persist(reference);
          }
          transaction.commit();
        } catch (RuntimeException failure) {
          if (transaction.isActive()) {
            transaction.rollback();
          }
          throw failure;
        }
      }
    }

    List<JavaSymbolIndex> pathSymbols() throws IOException {
      try (var session = sessionFactory().openSession()) {
        var hits = Search.session(session).search(JavaSymbolIndex.class)
            .where(f -> f.and(f.match().field("repositoryName").matching(REPOSITORY),
                f.match().field("path").matching(changed.path())))
            .fetchHits(100);
        for (var hit : hits) {
          verifySource(hit.getPath(), hit.getBlobId(), hit.getCommitId());
        }
        var expected = expectedCurrent.stream()
            .filter(fact -> fact.kind().equals("symbol") && fact.path().equals(changed.path())).toList();
        if (!facts(hits, List.of()).equals(expected)) {
          throw new IllegalStateException("Path query differs from the current source projection");
        }
        return hits;
      }
    }

    List<JavaReferenceIndex> methodReferences() throws IOException {
      try (var session = sessionFactory().openSession()) {
        var hits = Search.session(session).search(JavaReferenceIndex.class)
            .where(f -> f.and(f.match().field("repositoryName").matching(REPOSITORY),
                f.match().field("referenceKind").matching(JavaReferenceKind.METHOD_INVOCATION),
                f.match().field("referenceName_keyword").matching("value")))
            .fetchHits(100);
        for (var hit : hits) {
          verifySource(hit.getPath(), hit.getBlobId(), hit.getCommitId());
        }
        var expected = expectedCurrent.stream()
            .filter(fact -> fact.kind().equals("reference")
                && fact.fields().get(1).equals(JavaReferenceKind.METHOD_INVOCATION.name())
                && fact.fields().get(3).equals("value")).toList();
        if (hits.isEmpty() || !facts(List.of(), hits).equals(expected)) {
          throw new IllegalStateException("Method query differs from the current source projection");
        }
        return hits;
      }
    }

    List<SemanticFact> currentFacts() throws IOException {
      try (var session = sessionFactory().openSession()) {
        var symbols = session.createSelectionQuery("from JavaSymbolIndex", JavaSymbolIndex.class).getResultList();
        var references = session.createSelectionQuery("from JavaReferenceIndex", JavaReferenceIndex.class).getResultList();
        for (var symbol : symbols) {
          verifySource(symbol.getPath(), symbol.getBlobId(), symbol.getCommitId());
        }
        for (var reference : references) {
          verifySource(reference.getPath(), reference.getBlobId(), reference.getCommitId());
        }
        return facts(symbols, references);
      }
    }

    private void verifySource(String path, String blob, String origin) throws IOException {
      var member = current.sources().get(path);
      if (member == null || !member.blobId().equals(blob)) {
        throw new IllegalStateException("Semantic hit is not a member of the current Git snapshot");
      }
      try (var revisions = new RevWalk(repository);
          var walk = TreeWalk.forPath(repository, path,
              revisions.parseCommit(ObjectId.fromString(origin)).getTree())) {
        if (walk == null || !walk.getObjectId(0).name().equals(blob)) {
          throw new IllegalStateException("Semantic hit has invalid original Git provenance");
        }
      }
    }

    @TearDown(Level.Iteration)
    public void verify() throws IOException {
      if (!expectedCurrent.equals(currentFacts())) {
        throw new IllegalStateException("Persisted facts differ from the independent current snapshot analysis");
      }
      pathSymbols();
      methodReferences();
    }

    void reopen() throws IOException {
      provider.close();
      provider = null;
      shutdownDatabase();
      settings.setProperty("hibernate.hbm2ddl.auto", "validate");
      provider = new HibernateSessionFactoryProvider(settings, JavaAnalysisEntities.annotatedClasses());
    }

    private JavaProjectSnapshot snapshot(ObjectId commit) throws IOException {
      Map<String, JavaSourceSnapshot> sources = new LinkedHashMap<>();
      try (var revision = new RevWalk(repository); var walk = new TreeWalk(repository)) {
        walk.addTree(revision.parseCommit(commit).getTree());
        walk.setRecursive(true);
        while (walk.next()) {
          if (walk.getPathString().endsWith(".java")) {
            String path = walk.getPathString();
            ObjectId blob = walk.getObjectId(0);
            sources.put(path, new JavaSourceSnapshot(REPOSITORY, commit.name(), blob.name(), path,
                new String(repository.open(blob).getCachedBytes(), StandardCharsets.UTF_8)));
          }
        }
      }
      return new JavaProjectSnapshot(REPOSITORY, commit.name(), sources);
    }

    SessionFactory sessionFactory() { return provider.getSessionFactory(); }
    Path root() { return root; }
    String beforeCommit() { return before.commitId(); }
    String headCommit() { return head.commitId(); }
    List<SemanticFact> expectedHeadFacts() { return expectedHead; }

    Set<String> originCommits() {
      try (var session = sessionFactory().openSession()) {
        return session.createSelectionQuery("select distinct commitId from JavaSymbolIndex", String.class)
            .getResultStream().collect(Collectors.toSet());
      }
    }

    long analysisRuns() {
      try (var session = sessionFactory().openSession()) {
        return session.createSelectionQuery("select count(*) from JavaAnalysisRun", Long.class).getSingleResult();
      }
    }

    long indexBytes() throws IOException {
      return ConsumerBenchmarkFixtures.liveDirectoryBytes(indexRoot);
    }

    private void shutdownDatabase() throws IOException {
      if (settings == null) {
        return;
      }
      try (var connection = DriverManager.getConnection(settings.getProperty("hibernate.connection.url"));
          var statement = connection.createStatement()) {
        statement.execute("SHUTDOWN");
      } catch (SQLException failure) {
        throw new IOException("Could not stop the isolated sandbox database", failure);
      }
    }

    @TearDown(Level.Trial)
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        if (provider != null) {
          provider.close();
        }
      } finally {
        try {
          shutdownDatabase();
        } finally {
          if (repository != null) {
            repository.close();
          }
          if (root != null) {
            try (var paths = Files.walk(root)) {
              for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
              }
            }
          }
        }
      }
    }
  }

  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Counters {
    public long javaFilesAnalyzed;
    public long resultCount;
    public long preparedStatements;
    public long transactions;
    public long jdbcBatches;
    public long indexBytes;

    private void capture(Projection fixture) throws IOException {
      var statistics = fixture.sessionFactory().getStatistics();
      preparedStatements = statistics.getPrepareStatementCount();
      transactions = statistics.getTransactionCount();
      jdbcBatches = JdbcBatchMetricsSessionEventListener.snapshotCurrentThread().batchExecutions();
      indexBytes = fixture.indexBytes();
    }
  }

  record SemanticFact(String kind, String path, String blob, List<String> fields) {}

  private static SemanticFact fact(String kind, String path, String blob, Object... fields) {
    return new SemanticFact(kind, path, blob,
        Arrays.stream(fields).map(value -> value == null ? "<null>" : value.toString()).toList());
  }

  private static List<SemanticFact> facts(List<JavaSymbolIndex> symbols, List<JavaReferenceIndex> references) {
    List<SemanticFact> result = new ArrayList<>();
    for (var value : symbols) {
      result.add(fact("symbol", value.getPath(), value.getBlobId(), value.getRepositoryName(),
          value.getSymbolKind(), value.getBindingStatus(), value.getPackageName(), value.getSimpleName(),
          value.getQualifiedName(), value.getDeclaringType(), value.getSignature(), value.getReturnType(),
          value.getParameterTypes(), value.getModifiers(), value.getAnnotations(), value.getRawBindingKey(),
          value.getDeclarationBindingKey(), value.getDeclaringTypeBindingKey(), value.getTypeBindingKey(),
          value.getStableSemanticKey(), value.getStartLine(), value.getEndLine(), value.getStartPosition(),
          value.getSourceLength()));
    }
    for (var value : references) {
      result.add(fact("reference", value.getPath(), value.getBlobId(), value.getRepositoryName(),
          value.getReferenceKind(), value.getBindingStatus(), value.getReferenceName(), value.getSourceSymbolKey(),
          value.getRawBindingKey(), value.getDeclarationBindingKey(), value.getTargetTypeBindingKey(),
          value.getTargetStableSemanticKey(), value.getStartLine(), value.getEndLine(), value.getStartPosition(),
          value.getSourceLength()));
    }
    result.sort(Comparator.comparing(SemanticFact::toString));
    return List.copyOf(result);
  }
}
