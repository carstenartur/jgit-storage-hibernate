/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Runs representative JGit workloads against filesystem and Hibernate-backed repository variants.
 *
 * <p>The measured methods use only the public {@link Repository} API. Backend construction, schema
 * creation and cleanup stay outside measured invocations. PostgreSQL connection properties are
 * supplied by the JUnit/Testcontainers harness to every JMH fork.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
public class HibernateRepositoryBenchmark {

  static final String FILESYSTEM = "filesystem";
  static final String HSQLDB = "hsqldb";
  static final String POSTGRESQL = "postgresql";
  static final String POSTGRESQL_HIKARI = "postgresql-hikari";
  static final String POSTGRESQL_URL_PROPERTY = "jgit.storage.benchmark.postgresql.url";
  static final String POSTGRESQL_USER_PROPERTY = "jgit.storage.benchmark.postgresql.user";
  static final String POSTGRESQL_PASSWORD_PROPERTY = "jgit.storage.benchmark.postgresql.password";

  private static final int BULK_BLOB_COUNT = 100;
  private static final int COMMIT_SERIES_LENGTH = 10;
  private static final String PAYLOAD_PADDING = "x".repeat(1024);

  private final AtomicInteger counter = new AtomicInteger();

  @Param({FILESYSTEM, HSQLDB, POSTGRESQL, POSTGRESQL_HIKARI})
  public String backend;

  private HibernateSessionFactoryProvider provider;
  private Repository repository;
  private Path repositoryDirectory;
  private String repositoryName;
  private ObjectId blobId;
  private ObjectId commitId;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    repositoryName = "jmh-" + backend + "-" + Long.toHexString(System.nanoTime());
    repository = createRepository();
    repository.create(true);
    blobId = writeBlob("initial blob");
    commitId = writeCommitWithFile("Initial commit", "README.md", "initial content");
    updateRef("refs/heads/main", commitId);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    if (repository != null) {
      repository.close();
    }
    if (provider != null) {
      provider.close();
    }
    deleteRecursively(repositoryDirectory);
  }

  /** Measures the fixed cost of one tiny object insertion and immediate durable flush. */
  @Benchmark
  public ObjectId writeBlob() throws Exception {
    return writeBlob("payload-" + counter.incrementAndGet());
  }

  /**
   * Measures repeated reads of one already accessed object through JGit's normal caches.
   *
   * <p>This intentionally represents hot application-level lookup latency, not a physical disk or
   * database round trip.
   */
  @Benchmark
  public byte[] readBlobFromWarmCache() throws Exception {
    return readBlob(blobId);
  }

  /**
   * Measures a cache-cold JGit object lookup while leaving operating-system and database caches warm.
   */
  @Benchmark
  public byte[] readBlobAfterJGitCacheReset() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    return readBlob(blobId);
  }

  /** Measures resolving a frequently used ref while the repository instance remains open. */
  @Benchmark
  public ObjectId resolveMainOnOpenRepository() throws Exception {
    Ref main = repository.exactRef("refs/heads/main");
    if (main == null || main.getObjectId() == null) {
      throw new IllegalStateException("refs/heads/main disappeared");
    }
    return main.getObjectId();
  }

  /** Measures one small commit and one independent ref publication. */
  @Benchmark
  public ObjectId writeCommitAndUpdateRef() throws Exception {
    int id = counter.incrementAndGet();
    ObjectId commit = writeCommitWithFile("Commit " + id, "file-" + id + ".txt", "content " + id);
    updateRef("refs/heads/bench-" + id, commit);
    return commit;
  }

  /**
   * Measures an application-style batch: one inserter writes 100 roughly one KiB blobs and flushes
   * once, amortizing transaction and pack-publication overhead.
   */
  @Benchmark
  public ObjectId writeBatchOf100Blobs() throws Exception {
    int batchId = counter.incrementAndGet();
    ObjectId last = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 0; index < BULK_BLOB_COUNT; index++) {
        byte[] payload =
            ("batch-" + batchId + "-" + index + "-" + PAYLOAD_PADDING)
                .getBytes(StandardCharsets.UTF_8);
        last = inserter.insert(Constants.OBJ_BLOB, payload);
      }
      inserter.flush();
    }
    return last;
  }

  /**
   * Measures ten linked commits written by one inserter and published through a single main-ref
   * update, approximating an imported or synchronized change set.
   */
  @Benchmark
  public ObjectId writeCommitSeries10AndUpdateMain() throws Exception {
    Ref main = repository.exactRef("refs/heads/main");
    if (main == null || main.getObjectId() == null) {
      throw new IllegalStateException("refs/heads/main disappeared");
    }
    ObjectId parent = main.getObjectId();
    int seriesId = counter.incrementAndGet();
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 0; index < COMMIT_SERIES_LENGTH; index++) {
        ObjectId blob =
            inserter.insert(
                Constants.OBJ_BLOB,
                ("series-" + seriesId + "-" + index + "-" + PAYLOAD_PADDING)
                    .getBytes(StandardCharsets.UTF_8));
        TreeFormatter tree = new TreeFormatter();
        tree.append("file-" + index + ".txt", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        commit.setParentId(parent);
        commit.setAuthor(new PersonIdent("Benchmark", "benchmark@example.invalid"));
        commit.setCommitter(new PersonIdent("Benchmark", "benchmark@example.invalid"));
        commit.setMessage("Series " + seriesId + " commit " + index);
        parent = inserter.insert(commit);
      }
      inserter.flush();
    }
    updateRef("refs/heads/main", parent);
    return parent;
  }

  /** Measures repository reconstruction plus resolution of its primary ref. */
  @Benchmark
  public ObjectId reopenAndResolveMain() throws Exception {
    repository.close();
    repository = reopenRepository();
    Ref main = repository.exactRef("refs/heads/main");
    if (main == null || main.getObjectId() == null) {
      throw new IllegalStateException("refs/heads/main disappeared after reopen");
    }
    return main.getObjectId();
  }

  private byte[] readBlob(ObjectId objectId) throws Exception {
    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader loader = reader.open(objectId);
      return loader.getBytes();
    }
  }

  private Repository createRepository() throws IOException {
    return switch (backend) {
      case FILESYSTEM -> createFilesystemRepository();
      case HSQLDB -> createHibernateRepository(hsqlDbProperties(repositoryName));
      case POSTGRESQL -> createHibernateRepository(postgreSqlProperties(false));
      case POSTGRESQL_HIKARI -> createHibernateRepository(postgreSqlProperties(true));
      default -> throw new IllegalArgumentException("Unsupported benchmark backend: " + backend);
    };
  }

  private Repository reopenRepository() throws IOException {
    if (FILESYSTEM.equals(backend)) {
      return new FileRepositoryBuilder()
          .setGitDir(repositoryDirectory.toFile())
          .setBare()
          .setMustExist(true)
          .build();
    }
    return HibernateRepository.create(provider.getSessionFactory(), repositoryName);
  }

  private Repository createFilesystemRepository() throws IOException {
    repositoryDirectory = Files.createTempDirectory("jgit-filesystem-benchmark-");
    return new FileRepositoryBuilder()
        .setGitDir(repositoryDirectory.toFile())
        .setBare()
        .build();
  }

  private Repository createHibernateRepository(Properties properties) throws IOException {
    provider = new HibernateSessionFactoryProvider(properties);
    return HibernateRepository.create(provider.getSessionFactory(), repositoryName);
  }

  private ObjectId writeBlob(String content) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId id = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      inserter.flush();
      return id;
    }
  }

  private ObjectId writeCommitWithFile(String message, String path, String content) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId newBlobId = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, newBlobId);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(new PersonIdent("Benchmark", "benchmark@example.invalid"));
      commit.setCommitter(new PersonIdent("Benchmark", "benchmark@example.invalid"));
      commit.setMessage(message);
      ObjectId newCommitId = inserter.insert(commit);
      inserter.flush();
      return newCommitId;
    }
  }

  private void updateRef(String refName, ObjectId objectId) throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setNewObjectId(objectId);
    RefUpdate.Result result = update.update();
    if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
      throw new IllegalStateException("Unexpected ref update result " + result + " for " + refName);
    }
  }

  private static Properties hsqlDbProperties(String name) {
    Properties properties = commonHibernateProperties();
    properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + name);
    properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
    return properties;
  }

  private static Properties postgreSqlProperties(boolean hikari) {
    Properties properties = commonHibernateProperties();
    properties.put("hibernate.connection.url", requiredSystemProperty(POSTGRESQL_URL_PROPERTY));
    properties.put(
        "hibernate.connection.username", requiredSystemProperty(POSTGRESQL_USER_PROPERTY));
    properties.put(
        "hibernate.connection.password", requiredSystemProperty(POSTGRESQL_PASSWORD_PROPERTY));
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    if (hikari) {
      properties.put("hibernate.hikari.maximumPoolSize", "4");
      properties.put("hibernate.hikari.minimumIdle", "1");
      properties.put("hibernate.hikari.connectionTimeout", "10000");
      properties.put("hibernate.hikari.poolName", "jgit-storage-hibernate-benchmark");
    }
    return properties;
  }

  private static Properties commonHibernateProperties() {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    return properties;
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing PostgreSQL benchmark system property "
              + name
              + "; run the benchmark through the Maven benchmark-comparison profile");
    }
    return value;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
