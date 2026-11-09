/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.benchmark.BenchmarkConnectionProperties;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.repository.StagedGitRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StagedGitPublicationExperimentTest {
  @TempDir Path scratch;

  @ParameterizedTest
  @MethodSource("modesAndPolicies")
  void publishesACommitWhoseTreeAndPayloadSurviveAColdReopen(
      StagedChunkPipelineExperiment.Mode mode, boolean prePersist) throws Exception {
    Path input = fixture();
    byte[] payload = Files.readAllBytes(input);
    try (var provider = new HibernateSessionFactoryProvider(properties(prePersist))) {
      var experiment = new StagedGitPublicationExperiment(mode, 0);
      try (var repository =
          StagedGitRepositoryFactory.open(
              provider.getSessionFactory(), "git-pipeline", experiment)) {
        repository.create(true);
        var result = experiment.publish(repository, input);
        assertEquals(
            mode == StagedChunkPipelineExperiment.Mode.SYNCHRONOUS ? 1 : 2,
            result.retainedWindows());
        assertTrue(result.elapsedNanos() > 0);
        assertEquals(
            prePersist ? 1 : 0,
            repository.getPackPublicationSelectionMetrics().prePersistedSelections());
      }
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
      try (var reopened = HibernateRepository.create(provider.getSessionFactory(), "git-pipeline");
          var walk = new RevWalk(reopened)) {
        var ref = reopened.exactRef("refs/heads/staging-pipeline");
        assertNotNull(ref, "a complete Git publication must durably publish its ref");
        var commit = walk.parseCommit(ref.getObjectId());
        assertEquals(0, commit.getParentCount());
        var packs = reopened.getObjectDatabase().getPacks();
        assertEquals(1, packs.length);
        var description = packs[0].getPackDescription();
        assertEquals(3, description.getObjectCount());
        assertEquals(2, description.getIndexVersion());
        assertTrue(description.hasFileExt(PackExt.PACK));
        assertTrue(description.hasFileExt(PackExt.INDEX));
        try (var tree = TreeWalk.forPath(reopened, "payload.bin", commit.getTree());
            var stream = reopened.open(tree.getObjectId(0)).openStream()) {
          assertEquals(payload.length, stream.getSize());
          var digest = MessageDigest.getInstance("SHA-256");
          byte[] buffer = new byte[64 * 1024];
          for (int count; (count = stream.read(buffer)) != -1; ) {
            digest.update(buffer, 0, count);
          }
          assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), digest.digest());
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("modesAndPolicies")
  void failedTransferStaysInvisibleAndRollsBackEveryPackAndChunk(
      StagedChunkPipelineExperiment.Mode mode, boolean prePersist) throws Exception {
    Path input = fixture();
    try (var provider = new HibernateSessionFactoryProvider(properties(prePersist));
        var observer = HibernateRepository.create(provider.getSessionFactory(), "git-pipeline")) {
      observer.create(true);
      long packsBefore = count(provider, "GitPackEntity");
      long memoryBefore = PackExtensionStagingBuffer.retainedMemoryBytes();
      var visits = new AtomicInteger();
      var experiment =
          new StagedGitPublicationExperiment(
              mode,
              0,
              () -> {
                assertEquals(0, observer.getObjectDatabase().getPacks().length);
                assertNull(observer.exactRef(StagedGitPublicationExperiment.REF));
                visits.incrementAndGet();
                throw new IOException("injected failure after chunk transfer");
              });
      try (var repository =
          StagedGitRepositoryFactory.open(
              provider.getSessionFactory(), "git-pipeline", experiment)) {
        assertThrows(IOException.class, () -> experiment.publish(repository, input));
        assertEquals(
            prePersist ? 1 : 0,
            repository.getPackPublicationSelectionMetrics().prePersistedSelections());
      }
      assertEquals(1, visits.get());
      assertEquals(packsBefore, count(provider, "GitPackEntity"));
      assertEquals(0, count(provider, "GitPackChunkEntity"));
      assertEquals(memoryBefore, PackExtensionStagingBuffer.retainedMemoryBytes());
      try (var reopened =
          HibernateRepository.create(provider.getSessionFactory(), "git-pipeline")) {
        assertEquals(0, reopened.getObjectDatabase().getPacks().length);
        assertNull(reopened.exactRef(StagedGitPublicationExperiment.REF));
      }
    }
  }

  private Path fixture() throws IOException {
    byte[] payload = new byte[3 * 1024 * 1024 + 257];
    new Random(189).nextBytes(payload);
    return Files.write(scratch.resolve("payload.bin"), payload);
  }

  private static long count(HibernateSessionFactoryProvider provider, String entity) {
    try (var session = provider.getSessionFactory().openSession()) {
      return session
          .createSelectionQuery("SELECT count(e) FROM " + entity + " e", Long.class)
          .getSingleResult();
    }
  }

  private static Stream<Arguments> modesAndPolicies() {
    return Arrays.stream(StagedChunkPipelineExperiment.Mode.values())
        .flatMap(mode -> Stream.of(Arguments.of(mode, false), Arguments.of(mode, true)));
  }

  private static Properties properties(boolean prePersist) {
    BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
    var properties = new Properties();
    properties.setProperty(
        "hibernate.connection.url",
        "jdbc:hsqldb:mem:git-pipeline-" + UUID.randomUUID() + ";hsqldb.tx=mvcc");
    properties.setProperty("hibernate.connection.username", "sa");
    properties.setProperty("hibernate.connection.password", "");
    properties.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    properties.setProperty("hibernate.search.enabled", "false");
    properties.setProperty(HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, "1");
    properties.setProperty(HibernateStorageSettings.JDBC_BATCH_SIZE, "1");
    properties.setProperty(
        HibernateStorageSettings.PACK_PREPERSIST_MIN_PAYLOAD_BYTES,
        Long.toString(prePersist ? 1024 * 1024 : Long.MAX_VALUE));
    properties.setProperty(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return properties;
  }
}
