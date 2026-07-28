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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UploadPack;
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
 * Measures real JGit upload-pack and receive-pack workflows against each storage backend.
 *
 * <p>The clients are fresh in-memory repositories so the measurements emphasize server-side pack
 * generation, ingestion, ref publication and backend persistence. Repository construction, history
 * preparation and result verification happen outside the measured invocation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
public class GitProtocolBenchmark {

  private static final int INITIAL_HISTORY_COMMITS = 24;
  private static final int BASE_HISTORY_COMMITS = 20;
  private static final int INCREMENTAL_COMMITS = 4;
  private static final int PAYLOAD_BYTES = 32 * 1024;
  private static final String SOURCE_REF = "refs/heads/source";
  private static final String LOCAL_MAIN_REF = "refs/heads/main";
  private static final String CLONE_REMOTE_REF = "refs/heads/protocol-clone";
  private static final String FETCH_BASE_REMOTE_REF = "refs/heads/protocol-fetch-base";
  private static final String FETCH_TIP_REMOTE_REF = "refs/heads/protocol-fetch-tip";

  private final AtomicInteger invocationCounter = new AtomicInteger();
  private final Object connectionContext = new Object();

  @Param({
    HibernateRepositoryBenchmark.FILESYSTEM,
    HibernateRepositoryBenchmark.HSQLDB,
    HibernateRepositoryBenchmark.POSTGRESQL,
    HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
  })
  public String backend;

  private HibernateSessionFactoryProvider provider;
  private Repository server;
  private Path serverDirectory;
  private TestProtocol<Object> protocol;
  private URIish serverUri;

  private ObjectId cloneTip;
  private ObjectId fetchBaseTip;
  private ObjectId fetchTip;

  private InMemoryRepository initialPushClient;
  private ObjectId initialPushTip;
  private String initialPushRemoteRef;

  private InMemoryRepository incrementalPushClient;
  private ObjectId incrementalPushBase;
  private ObjectId incrementalPushTip;
  private String incrementalPushRemoteRef;

  private InMemoryRepository initialCloneClient;
  private InMemoryRepository incrementalFetchClient;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    String serverName = "jmh-protocol-" + backend + "-" + Long.toHexString(System.nanoTime());
    server = createServerRepository(serverName);
    server.create(true);

    cloneTip =
        writeHistory(
            server,
            null,
            INITIAL_HISTORY_COMMITS,
            PAYLOAD_BYTES,
            0x434c4f4e,
            CLONE_REMOTE_REF);
    fetchBaseTip =
        writeHistory(
            server,
            null,
            BASE_HISTORY_COMMITS,
            PAYLOAD_BYTES,
            0x46455443,
            FETCH_BASE_REMOTE_REF);
    fetchTip =
        writeHistory(
            server,
            fetchBaseTip,
            INCREMENTAL_COMMITS,
            PAYLOAD_BYTES,
            0x44454c54,
            FETCH_TIP_REMOTE_REF);

    protocol =
        new TestProtocol<>(
            (Object request, Repository repository) -> new UploadPack(repository),
            (Object request, Repository repository) -> new ReceivePack(repository));
    Transport.register(protocol);
    serverUri = protocol.register(connectionContext, server);
  }

  @Setup(Level.Invocation)
  public void setupInvocation() throws Exception {
    int invocation = invocationCounter.incrementAndGet();

    initialPushClient = newMemoryRepository("initial-push-" + invocation);
    initialPushTip =
        writeHistory(
            initialPushClient,
            null,
            INITIAL_HISTORY_COMMITS,
            PAYLOAD_BYTES,
            0x10000000 + invocation,
            SOURCE_REF);
    initialPushRemoteRef = "refs/heads/initial-push-" + invocation;

    incrementalPushClient = newMemoryRepository("incremental-push-" + invocation);
    incrementalPushBase =
        writeHistory(
            incrementalPushClient,
            null,
            BASE_HISTORY_COMMITS,
            PAYLOAD_BYTES,
            0x20000000 + invocation,
            SOURCE_REF);
    incrementalPushRemoteRef = "refs/heads/incremental-push-" + invocation;
    push(
        incrementalPushClient,
        SOURCE_REF,
        incrementalPushRemoteRef,
        ObjectId.zeroId(),
        incrementalPushBase);
    incrementalPushTip =
        writeHistory(
            incrementalPushClient,
            incrementalPushBase,
            INCREMENTAL_COMMITS,
            PAYLOAD_BYTES,
            0x30000000 + invocation,
            SOURCE_REF);

    initialCloneClient = newMemoryRepository("initial-clone-" + invocation);

    incrementalFetchClient = newMemoryRepository("incremental-fetch-" + invocation);
    fetch(incrementalFetchClient, FETCH_BASE_REMOTE_REF, LOCAL_MAIN_REF, fetchBaseTip);
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() {
    close(initialPushClient);
    close(incrementalPushClient);
    close(initialCloneClient);
    close(incrementalFetchClient);
    initialPushClient = null;
    incrementalPushClient = null;
    initialCloneClient = null;
    incrementalFetchClient = null;
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() throws IOException {
    if (protocol != null) {
      Transport.unregister(protocol);
    }
    if (server != null) {
      server.close();
    }
    if (provider != null) {
      provider.close();
    }
    deleteRecursively(serverDirectory);
  }

  /** Receives an unrelated complete history and creates a new remote branch. */
  @Benchmark
  public ObjectId initialPushViaReceivePack() throws Exception {
    push(
        initialPushClient,
        SOURCE_REF,
        initialPushRemoteRef,
        ObjectId.zeroId(),
        initialPushTip);
    return initialPushTip;
  }

  /** Receives four descendants after the server already has the twenty-commit base history. */
  @Benchmark
  public ObjectId incrementalPushViaReceivePack() throws Exception {
    push(
        incrementalPushClient,
        SOURCE_REF,
        incrementalPushRemoteRef,
        incrementalPushBase,
        incrementalPushTip);
    return incrementalPushTip;
  }

  /** Fetches a complete history into an otherwise empty bare repository. */
  @Benchmark
  public ObjectId initialCloneViaUploadPack() throws Exception {
    fetch(initialCloneClient, CLONE_REMOTE_REF, LOCAL_MAIN_REF, cloneTip);
    return cloneTip;
  }

  /** Fetches only four descendants into a client that already has the twenty-commit base. */
  @Benchmark
  public ObjectId incrementalFetchViaUploadPack() throws Exception {
    fetch(incrementalFetchClient, FETCH_TIP_REMOTE_REF, LOCAL_MAIN_REF, fetchTip);
    return fetchTip;
  }

  private void push(
      Repository client,
      String localRef,
      String remoteRef,
      ObjectId expectedOld,
      ObjectId expectedTip)
      throws Exception {
    RemoteRefUpdate update =
        new RemoteRefUpdate(client, localRef, remoteRef, false, null, expectedOld);
    try (Transport transport = Transport.open(client, serverUri)) {
      transport.push(NullProgressMonitor.INSTANCE, List.of(update));
    }
    if (update.getStatus() != RemoteRefUpdate.Status.OK
        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
      throw new IllegalStateException(
          "Push of " + remoteRef + " failed with " + update.getStatus() + ": " + update.getMessage());
    }
    Ref remote = server.exactRef(remoteRef);
    if (remote == null || !expectedTip.equals(remote.getObjectId())) {
      throw new IllegalStateException("Remote ref " + remoteRef + " did not reach " + expectedTip);
    }
  }

  private void fetch(
      Repository client, String remoteRef, String localRef, ObjectId expectedTip) throws Exception {
    try (Transport transport = Transport.open(client, serverUri)) {
      transport.fetch(
          NullProgressMonitor.INSTANCE, List.of(new RefSpec(remoteRef + ":" + localRef)));
    }
    Ref local = client.exactRef(localRef);
    if (local == null || !expectedTip.equals(local.getObjectId())) {
      throw new IllegalStateException("Local ref " + localRef + " did not reach " + expectedTip);
    }
  }

  private static ObjectId writeHistory(
      Repository repository,
      ObjectId parent,
      int commitCount,
      int payloadBytes,
      int seed,
      String refName)
      throws Exception {
    Random random = new Random(seed);
    ObjectId tip = parent;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 0; index < commitCount; index++) {
        byte[] payload = new byte[payloadBytes];
        random.nextBytes(payload);
        payload[0] ^= (byte) index;
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);

        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (tip != null) {
          commit.setParentId(tip);
        }
        commit.setAuthor(new PersonIdent("Protocol benchmark", "benchmark@example.invalid"));
        commit.setCommitter(new PersonIdent("Protocol benchmark", "benchmark@example.invalid"));
        commit.setMessage("Protocol history " + seed + " commit " + index);
        tip = inserter.insert(commit);
      }
      inserter.flush();
    }
    updateRef(repository, refName, tip);
    return tip;
  }

  private static void updateRef(Repository repository, String refName, ObjectId objectId)
      throws IOException {
    RefUpdate update = repository.updateRef(refName);
    update.setNewObjectId(objectId);
    RefUpdate.Result result = update.update();
    if (result != RefUpdate.Result.NEW
        && result != RefUpdate.Result.FAST_FORWARD
        && result != RefUpdate.Result.FORCED) {
      throw new IOException("Unexpected ref update result " + result + " for " + refName);
    }
  }

  private Repository createServerRepository(String name) throws IOException {
    return switch (backend) {
      case HibernateRepositoryBenchmark.FILESYSTEM -> createFilesystemRepository();
      case HibernateRepositoryBenchmark.HSQLDB -> createHibernateRepository(hsqlDbProperties(name), name);
      case HibernateRepositoryBenchmark.POSTGRESQL ->
          createHibernateRepository(postgreSqlProperties(false), name);
      case HibernateRepositoryBenchmark.POSTGRESQL_HIKARI ->
          createHibernateRepository(postgreSqlProperties(true), name);
      default -> throw new IllegalArgumentException("Unsupported benchmark backend: " + backend);
    };
  }

  private Repository createFilesystemRepository() throws IOException {
    serverDirectory = Files.createTempDirectory("jgit-protocol-filesystem-benchmark-");
    return new FileRepositoryBuilder().setGitDir(serverDirectory.toFile()).setBare().build();
  }

  private Repository createHibernateRepository(Properties properties, String name)
      throws IOException {
    provider = new HibernateSessionFactoryProvider(properties);
    return HibernateRepository.create(provider.getSessionFactory(), name);
  }

  private static InMemoryRepository newMemoryRepository(String name) throws IOException {
    InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription("jmh-" + name));
    repository.create(true);
    return repository;
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
    if (hikari) {
      properties.put("hibernate.hikari.maximumPoolSize", "4");
      properties.put("hibernate.hikari.minimumIdle", "1");
      properties.put("hibernate.hikari.connectionTimeout", "10000");
      properties.put("hibernate.hikari.poolName", "jgit-storage-hibernate-protocol-benchmark");
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
              + "; run through the Maven benchmark-comparison profile");
    }
    return value;
  }

  private static void close(Repository repository) {
    if (repository != null) {
      repository.close();
    }
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
