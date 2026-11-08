/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/** A small native follow-up: identical workspace deltas and Audio publication WAL. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ConsumerPublicationBenchmark {
  private static final String MAIN = "refs/heads/main";
  private static final String PROPOSALS = "refs/heads/proposals";
  private static final String ARTIFACTS = "refs/heads/artifacts";
  private static final int WORKSPACES = 4;

  @Benchmark
  public int taxonomyPublishDelta(Taxonomy fixture, Counters counters) throws IOException {
    int publication = ++fixture.publication;
    List<Callable<Published>> tasks = new ArrayList<>();
    for (int index = 0; index < WORKSPACES; index++) {
      int workspace = index;
      tasks.add(() -> {
        JdbcBatchMetricsSessionEventListener.resetCurrentThread();
        var repository = fixture.store.repository(workspace);
        ObjectId next = ConsumerBenchmarkFixtures.publishTaxonomyDelta(repository, workspace,
            publication, fixture.heads.get(workspace));
        new CommitIndexer(fixture.sessionFactory(), repository.getIdentifier()).indexCommit(repository, next);
        return new Published(next, JdbcBatchMetricsSessionEventListener.snapshotCurrentThread().batchExecutions(),
            Thread.currentThread().threadId());
      });
    }
    var published = fixture.workers.execute(tasks);
    for (int index = 0; index < WORKSPACES; index++) {
      ObjectId next = published.get(index).head();
      fixture.reflogs.get(index).add(0, fixture.heads.get(index).name() + ":" + next.name());
      fixture.heads.set(index, next);
      fixture.members.get(index).add(next.name());
    }
    fixture.lastWorkers = published.stream().map(Published::worker).distinct().count();
    counters.workspacesPublished = WORKSPACES;
    counters.commitsIndexed = WORKSPACES;
    counters.publicationWorkers = fixture.lastWorkers;
    fixture.store.capture(counters, published.stream().mapToLong(Published::batches).sum());
    return WORKSPACES;
  }

  @Benchmark
  public long audioPublishWithWal(Audio fixture, Counters counters) throws IOException {
    fixture.recent = ConsumerBenchmarkFixtures.publishAudio(fixture.store.repository(0), 17,
        ++fixture.publication, fixture.coordinate);
    counters.payloadBytes = fixture.coordinate;
    counters.workspacesPublished = 1;
    counters.publicationWorkers = 1;
    counters.retainedAnalysisBytes = Math.multiplyExact((long) fixture.analysisMiB, 1024 * 1024);
    fixture.store.capture(counters, JdbcBatchMetricsSessionEventListener.snapshotCurrentThread().batchExecutions());
    return fixture.coordinate;
  }

  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Counters {
    public long workspacesPublished, commitsIndexed, preparedStatements, payloadBytes,
        databaseBytesWritten, databaseBytesRead, retainedAnalysisBytes, transactions, jdbcBatches, publicationWorkers;

    @Setup(Level.Iteration)
    public void reset() {
      workspacesPublished = commitsIndexed = preparedStatements = payloadBytes = databaseBytesWritten =
          databaseBytesRead = retainedAnalysisBytes = transactions = jdbcBatches = publicationWorkers = 0;
    }
  }

  record Verification(List<String> heads, long gitCommits, long sqlCommits, long indexedCommits,
      String membershipSha256) {}
  private record Published(ObjectId head, long batches, long worker) {}

  @State(Scope.Thread)
  public static class Taxonomy implements AutoCloseable {
    @Param({"1000"}) public int coordinate = 1000;
    @Param({"1", "4"}) public int parallelism = 1;
    private ConsumerPublicationStore store;
    private ConsumerPublicationWorkers workers;
    private final List<ObjectId> heads = new ArrayList<>();
    private final List<ObjectId> proposals = new ArrayList<>();
    private final List<Set<String>> members = new ArrayList<>();
    private final List<List<String>> reflogs = new ArrayList<>();
    private final List<List<String>> proposalReflogs = new ArrayList<>();
    private int publication;
    private int measurement;
    private long lastWorkers;
    private boolean measured;

    @Setup(Level.Trial)
    public void setup() throws IOException {
      if (coordinate < 32 || coordinate % WORKSPACES != 0 || (parallelism != 1 && parallelism != 4)) {
        throw new IllegalArgumentException("Expected >=32 commits divisible by four and 1/4 workers");
      }
      try {
        store = new ConsumerPublicationStore(true);
        for (int workspace = 0; workspace < WORKSPACES; workspace++) {
          var repository = store.create("publication-taxonomy-" + workspace);
          ObjectId head = ConsumerBenchmarkFixtures.createTaxonomyHistory(repository, workspace, coordinate / WORKSPACES);
          int indexed = new CommitIndexer(sessionFactory(), repository.getIdentifier()).indexCommitsFrom(repository, head, -1);
          require(indexed == coordinate / WORKSPACES, "Initial projection omitted commits");
          heads.add(head);
          proposals.add(repository.resolve(PROPOSALS));
          members.add(reachable(repository, List.of(head)));
          reflogs.add(new ArrayList<>(reflog(repository, MAIN)));
          proposalReflogs.add(reflog(repository, PROPOSALS));
        }
        verify();
        restart();
        verify();
        workers = new ConsumerPublicationWorkers(parallelism);
      } catch (IOException | RuntimeException failure) {
        try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        throw failure;
      }
    }

    void begin() { store.begin(false); }

    @Setup(Level.Iteration)
    public void beginIteration(IterationParams iteration) {
      measured = iteration.getType() == IterationType.MEASUREMENT;
      store.begin(measured);
    }

    @TearDown(Level.Iteration)
    public void finishIteration() throws IOException {
      var delta = store.endTelemetry(); // Capture before any validation/read/reopen work.
      require(lastWorkers == parallelism, "Publication did not use the configured worker count");
      var before = verify();
      restart();
      require(before.equals(verify()), "Taxonomy restart changed verified state");
      if (measured) store.retain(delta, Map.of("consumerShape", "Taxonomy", "operation", "taxonomyPublishDelta",
          "coordinate", Integer.toString(coordinate), "parallelism", Integer.toString(parallelism),
          "workspaces", Integer.toString(WORKSPACES), "publication", Integer.toString(publication),
          "measurement", Integer.toString(++measurement)), before);
    }

    Verification verify() throws IOException {
      List<String> canonical = new ArrayList<>();
      long sqlCount = 0;
      long indexedCount = 0;
      long gitCount = 0;
      for (int workspace = 0; workspace < WORKSPACES; workspace++) {
        var repository = store.repository(workspace);
        require(heads.get(workspace).equals(repository.resolve(Constants.HEAD)), "Wrong Taxonomy HEAD");
        require(proposals.get(workspace).equals(repository.resolve(PROPOSALS)), "Proposal ref changed");
        require(repository.exactRef(Constants.HEAD).isSymbolic()
            && MAIN.equals(repository.exactRef(Constants.HEAD).getTarget().getName()), "HEAD target changed");
        require(refNames(repository).equals(Set.of(MAIN, PROPOSALS)), "Unexpected workspace refs");
        var git = reachable(repository, List.of(heads.get(workspace)));
        require(git.equals(members.get(workspace)), "Git history differs from expected complete membership");
        require(reflog(repository, MAIN).equals(reflogs.get(workspace)), "Workspace reflog changed");
        require(reflog(repository, PROPOSALS).equals(proposalReflogs.get(workspace)), "Proposal reflog changed");
        if (publication > 0) require(blobText(repository, heads.get(workspace), "requirements/concurrent.json")
            .equals("{\"workspace\":" + workspace + ",\"publication\":" + publication
                + ",\"topic\":\"terminology\"}\n"), "Published delta blob mismatch");
        try (var session = sessionFactory().openSession()) {
          var sql = session.createSelectionQuery(
              "select objectId from GitCommitIndex where repositoryName = :name order by objectId", String.class)
              .setParameter("name", repository.getIdentifier()).getResultList();
          var indexed = Search.session(session).search(GitCommitIndex.class)
              .select(f -> f.field("objectId", String.class))
              .where(f -> f.match().field("repositoryName").matching(repository.getIdentifier()))
              .fetchAllHits().stream().sorted().toList();
          var expected = git.stream().sorted().toList();
          require(sql.equals(expected), "SQL projection differs from authoritative Git membership");
          require(indexed.equals(expected), "Lucene projection differs from authoritative Git membership");
          sqlCount += sql.size();
          indexedCount += indexed.size();
        }
        if (publication > 0) verifyPublishedContent(repository, heads.get(workspace));
        gitCount += git.size();
        canonical.add(repository.getIdentifier() + "\n" + String.join("\n", git)
            + "\nmain=" + heads.get(workspace).name() + "\nproposal=" + proposals.get(workspace).name()
            + "\nreflog=" + reflogs.get(workspace) + "\nproposalReflog=" + proposalReflogs.get(workspace));
      }
      return new Verification(heads.stream().map(ObjectId::name).toList(), gitCount, sqlCount,
          indexedCount, digest(String.join("\n", canonical)));
    }

    int lastWorkerCount() { return Math.toIntExact(lastWorkers); }

    private void verifyPublishedContent(Repository repository, ObjectId head) throws IOException {
      List<String> published = new ArrayList<>();
      try (var walk = new RevWalk(repository); var session = sessionFactory().openSession()) {
        var commit = walk.parseCommit(head);
        for (int revision = 0; revision < publication; revision++) {
          var projection = session.createSelectionQuery(
              "from GitCommitIndex where repositoryName = :name and objectId = :id", GitCommitIndex.class)
              .setParameter("name", repository.getIdentifier()).setParameter("id", commit.name()).getSingleResult();
          var author = commit.getAuthorIdent();
          var committer = commit.getCommitterIdent();
          String path = "requirements/concurrent.json";
          require(projection.getShortMessage().equals(commit.getShortMessage())
              && projection.getFullMessage().equals(commit.getFullMessage())
              && projection.getAuthorName().equals(author.getName())
              && projection.getAuthorEmail().equals(author.getEmailAddress())
              && projection.getAuthorTime().equals(author.getWhenAsInstant())
              && projection.getCommitterName().equals(committer.getName())
              && projection.getCommitterEmail().equals(committer.getEmailAddress())
              && projection.getCommitterTime().equals(committer.getWhenAsInstant())
              && projection.getChangedPaths().equals(path)
              && projection.getChangedText().equals("\n--- " + path + " ---\n" + blobText(repository, commit, path)),
              "Published SQL projection content differs from authoritative Git");
          published.add(commit.name());
          commit = walk.parseCommit(commit.getParent(0));
        }
      }
      var search = new GitHistorySearchService(sessionFactory());
      for (boolean messages : List.of(false, true)) {
        var query = CommitHistoryQuery.forRepository(repository.getIdentifier())
            .restrictedToObjectIds(published).touchingExactPath("requirements/concurrent.json")
            .authoredBy("workspace-publisher@example.invalid").committedBy("workspace-publisher@example.invalid")
            .between(java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(1_000_001),
                java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(1_000_000 + publication))
            .limit(publication + 1);
        if (messages) query.matchingMessages("terminology");
        else query.matchingText("topic");
        var actual = search.findChangeSummaries(query.build()).stream().map(CommitSearchHit::objectId).sorted().toList();
        require(actual.equals(published.stream().sorted().toList()),
            "Published Lucene message/content/path/identity/time predicates differ from Git");
      }
    }

    void restart() throws IOException { store.restart(); }
    SessionFactory sessionFactory() { return store.sessionFactory(); }

    @TearDown(Level.Trial)
    @Override public void close() throws IOException {
      // A failed join deliberately prevents closing storage that workers may still access.
      if (workers != null) workers.close();
      if (store != null) store.close();
    }
  }

  @State(Scope.Thread)
  public static class Audio implements AutoCloseable {
    @Param({"2097152", "16777216"}) public int coordinate;
    @Param({"32"}) public int analysisMiB = 32;
    private ConsumerPublicationStore store;
    private ConsumerShapeBenchmark.AudioPressure pressure;
    private ConsumerBenchmarkFixtures.AudioPublication recent;
    private int publication;
    private int measurement;
    private boolean measured;

    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
      if (coordinate < 1 || analysisMiB < 1 || analysisMiB > 128) {
        throw new IllegalArgumentException("Expected positive payload and 1..128 MiB analysis working set");
      }
      try {
        store = new ConsumerPublicationStore(false);
        var repository = store.create("publication-audio");
        recent = ConsumerBenchmarkFixtures.publishAudio(repository, 17, 0, coordinate);
        verify();
        restart();
        verify();
        pressure = new ConsumerShapeBenchmark.AudioPressure(analysisMiB, 1);
      } catch (IOException | InterruptedException | RuntimeException failure) {
        try { close(); } catch (IOException | InterruptedException cleanup) { failure.addSuppressed(cleanup); }
        throw failure;
      }
    }

    void begin() { store.begin(false); }

    @Setup(Level.Iteration)
    public void beginIteration(IterationParams iteration) {
      measured = iteration.getType() == IterationType.MEASUREMENT;
      store.begin(measured);
    }

    @TearDown(Level.Iteration)
    public void finishIteration() throws IOException {
      var delta = store.endTelemetry();
      var before = verify();
      restart();
      require(before.equals(verify()), "Audio restart changed verified publication");
      if (measured) store.retain(delta, Map.of("consumerShape", "Audio", "operation", "audioPublishWithWal",
          "coordinate", Integer.toString(coordinate), "repositories", "1", "analysisMiB", Integer.toString(analysisMiB),
          "analysisWorkers", "1", "publication", Integer.toString(publication),
          "measurement", Integer.toString(++measurement)), before);
    }

    Verification verify() throws IOException {
      var repository = store.repository(0);
      require(recent.metadataCommit().equals(repository.resolve(Constants.HEAD)), "Wrong Audio HEAD");
      require(recent.artifactCommit().equals(repository.resolve(ARTIFACTS)), "Wrong artifact ref");
      require(refNames(repository).equals(Set.of(MAIN, ARTIFACTS)), "Unexpected Audio refs");
      require(repository.exactRef(Constants.HEAD).isSymbolic()
          && MAIN.equals(repository.exactRef(Constants.HEAD).getTarget().getName()), "Audio HEAD target changed");
      ObjectId blob = blobId(repository, recent.artifactCommit(), "artifact.bin");
      require(blob.equals(recent.payload()), "Artifact tree points at wrong payload");
      var loader = repository.open(blob, Constants.OBJ_BLOB);
      require(loader.getSize() == coordinate, "Audio payload length changed");
      try (var stream = loader.openStream(); var formatter = new ObjectInserter.Formatter()) {
        require(formatter.idFor(Constants.OBJ_BLOB, coordinate, stream).equals(recent.payload()),
            "Audio payload bytes do not match Git object identity");
      }
      require(blobText(repository, recent.metadataCommit(), "metadata/latest.json")
          .equals("{\"artifact\":\"" + recent.payload().name() + "\",\"bytes\":" + coordinate
              + ",\"publication\":" + publication + "}\n"), "Audio metadata mismatch");
      var git = reachable(repository, List.of(recent.metadataCommit(), recent.artifactCommit()));
      require(git.size() == 2 * (publication + 1), "Audio branch history omitted commits");
      // Atomic BatchRefUpdate stores its logs in the reftable, while single RefUpdate also has
      // the repository's durable SQL reflog API. Inspect the log owned by the operation used.
      var metadataLog = batchReflog(repository, MAIN);
      var artifactLog = batchReflog(repository, ARTIFACTS);
      require(metadataLog.size() == publication + 1 && artifactLog.size() == publication + 1,
          "Audio reflog omitted publication");
      verifyReflogChain(repository, recent.metadataCommit(), metadataLog);
      verifyReflogChain(repository, recent.artifactCommit(), artifactLog);
      return new Verification(List.of(recent.metadataCommit().name(), recent.artifactCommit().name()),
          git.size(), 0, 0, digest(String.join("\n", git) + metadataLog + artifactLog + recent.payload().name()));
    }

    void restart() throws IOException { store.restart(); }
    SessionFactory sessionFactory() { return store.sessionFactory(); }

    @TearDown(Level.Trial)
    @Override public void close() throws IOException, InterruptedException {
      if (pressure != null) pressure.close();
      if (store != null) store.close();
    }
  }

  private static Set<String> reachable(Repository repository, List<ObjectId> heads) throws IOException {
    Set<String> ids = new TreeSet<>();
    try (var walk = new RevWalk(repository)) {
      for (var head : heads) walk.markStart(walk.parseCommit(head));
      for (var commit : walk) ids.add(commit.name());
    }
    return ids;
  }

  private static Set<String> refNames(Repository repository) throws IOException {
    Set<String> names = new TreeSet<>();
    repository.getRefDatabase().getRefsByPrefix("refs/").forEach(ref -> names.add(ref.getName()));
    return names;
  }

  private static List<String> reflog(Repository repository, String ref) throws IOException {
    return repository.getReflogReader(ref).getReverseEntries().stream()
        .map(entry -> entry.getOldId().name() + ":" + entry.getNewId().name()).toList();
  }

  private static List<String> batchReflog(Repository repository, String ref) throws IOException {
    return repository.getRefDatabase().getReflogReader(repository.exactRef(ref)).getReverseEntries().stream()
        .map(entry -> entry.getOldId().name() + ":" + entry.getNewId().name()).toList();
  }

  private static void verifyReflogChain(Repository repository, ObjectId head, List<String> log) throws IOException {
    try (var walk = new RevWalk(repository)) {
      ObjectId cursor = head;
      for (String entry : log) {
        var commit = walk.parseCommit(cursor);
        ObjectId parent = commit.getParentCount() == 0 ? ObjectId.zeroId() : commit.getParent(0);
        require(entry.equals(parent.name() + ":" + cursor.name()), "Audio reflog differs from branch ancestry");
        cursor = parent;
      }
      require(cursor.equals(ObjectId.zeroId()), "Audio reflog does not cover complete history");
    }
  }

  private static ObjectId blobId(Repository repository, ObjectId commit, String path) throws IOException {
    try (var walk = new RevWalk(repository);
        var tree = TreeWalk.forPath(repository, path, walk.parseCommit(commit).getTree())) {
      require(tree != null, "Missing expected publication path");
      return tree.getObjectId(0);
    }
  }

  private static String blobText(Repository repository, ObjectId commit, String path) throws IOException {
    return new String(repository.open(blobId(repository, commit, path), Constants.OBJ_BLOB).getBytes(), StandardCharsets.UTF_8);
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
