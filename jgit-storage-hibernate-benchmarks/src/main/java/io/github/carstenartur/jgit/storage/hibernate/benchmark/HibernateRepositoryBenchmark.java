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
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
public class HibernateRepositoryBenchmark {

  private final AtomicInteger counter = new AtomicInteger();
  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private String repositoryName;
  private ObjectId blobId;
  private ObjectId commitId;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    repositoryName = "jmh-hibernate-repository-" + Long.toHexString(System.nanoTime());
    provider = new HibernateSessionFactoryProvider(h2Properties(repositoryName));
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    blobId = writeBlob("initial blob");
    commitId = writeCommitWithFile("Initial commit", "README.md", "initial content");
    updateRef("refs/heads/main", commitId);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (repository != null) {
      repository.close();
    }
    if (provider != null) {
      provider.close();
    }
  }

  @Benchmark
  public ObjectId writeBlob() throws Exception {
    return writeBlob("payload-" + counter.incrementAndGet());
  }

  @Benchmark
  public byte[] readBlob() throws Exception {
    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader loader = reader.open(blobId);
      return loader.getBytes();
    }
  }

  @Benchmark
  public ObjectId writeCommitAndUpdateRef() throws Exception {
    int id = counter.incrementAndGet();
    ObjectId commit = writeCommitWithFile("Commit " + id, "file-" + id + ".txt", "content " + id);
    updateRef("refs/heads/bench-" + id, commit);
    return commit;
  }

  @Benchmark
  public ObjectId reopenAndResolveMain() throws Exception {
    repository.close();
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    return repository.exactRef("refs/heads/main").getObjectId();
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

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
