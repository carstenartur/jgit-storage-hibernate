/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsPackFile;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

class PackRepackMaintenanceH2Test {

  private static final int COMMIT_COUNT = 18;
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Test
  void compactsIncrementalPacksAndPreservesReadOptimizedHistoryAcrossRestart()
      throws Exception {
    String repositoryName = "repack-" + UUID.randomUUID();
    ObjectId expectedTip;

    try (HibernateSessionFactoryProvider provider = provider()) {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
        expectedTip = writeHistory(repository);
        assertTrue(
            repository.getObjectDatabase().getPacks().length > 4,
            "The fixture must contain multiple incremental packs before maintenance");
      }

      PackRepackResult result =
          new PackStorageMaintenance(provider.getSessionFactory())
              .repackForReads(new RepositoryName(repositoryName));

      assertTrue(result.successful());
      assertTrue(result.packsBefore() > 4);
      assertTrue(result.packsAfter() < result.packsBefore());
      assertTrue(result.packReduction() > 0);
      assertTrue(result.sourcePackDescriptions() > 0);
      assertTrue(result.newPackDescriptions() > 0);
      assertTrue(result.elapsedNanos() > 0);
      assertEquals(result.elapsedNanos(), result.elapsed().toNanos());

      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        Ref main = reopened.exactRef("refs/heads/main");
        assertNotNull(main);
        assertEquals(expectedTip, main.getObjectId());
        assertHistory(reopened, expectedTip);

        DfsPackFile[] packs = reopened.getObjectDatabase().getPacks();
        assertEquals(result.packsAfter(), packs.length);
        DfsPackDescription gcPack =
            java.util.Arrays.stream(packs)
                .map(DfsPackFile::getPackDescription)
                .filter(description -> description.getPackSource() == PackSource.GC)
                .findFirst()
                .orElseThrow();
        assertTrue(gcPack.hasFileExt(PackExt.PACK));
        assertTrue(gcPack.hasFileExt(PackExt.INDEX));
        assertTrue(gcPack.hasFileExt(PackExt.BITMAP_INDEX));
        assertTrue(gcPack.hasFileExt(PackExt.REVERSE_INDEX));
        assertTrue(gcPack.hasFileExt(PackExt.COMMIT_GRAPH));
        assertTrue(gcPack.getObjectCount() > 0);
        assertTrue(gcPack.getLastModified() > 0);
        assertTrue(
            reopened.getObjectDatabase().getReftables().length <= result.reftablesBefore(),
            "Reftable compaction must not increase the active stack depth");
      }
    }
  }

  private static ObjectId writeHistory(HibernateRepository repository) throws Exception {
    ObjectId parent = null;
    for (int index = 0; index < COMMIT_COUNT; index++) {
      ObjectId commitId;
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        byte[] payload = new byte[32 * 1024];
        new Random(0x5eed0000L + index).nextBytes(payload);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);
        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(inserter.insert(tree));
        if (parent != null) {
          commit.setParentId(parent);
        }
        PersonIdent identity =
            new PersonIdent(
                "Maintenance Test",
                "maintenance@example.invalid",
                Date.from(Instant.ofEpochSecond(1_750_000_000L + index)),
                UTC);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Incremental commit " + index);
        commitId = inserter.insert(commit);
        inserter.flush();
      }

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(parent == null ? ObjectId.zeroId() : parent);
      update.setNewObjectId(commitId);
      update.disableRefLog();
      RefUpdate.Result updateResult = update.update();
      assertTrue(
          updateResult == RefUpdate.Result.NEW || updateResult == RefUpdate.Result.FAST_FORWARD,
          () -> "Unexpected ref update result " + updateResult);
      parent = commitId;
    }
    return parent;
  }

  private static void assertHistory(HibernateRepository repository, ObjectId expectedTip)
      throws Exception {
    int count = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit tip = walk.parseCommit(expectedTip);
      walk.markStart(tip);
      for (RevCommit ignored : walk) {
        count++;
      }
    }
    assertEquals(COMMIT_COUNT, count);
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:repack-maintenance-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    return new HibernateSessionFactoryProvider(properties);
  }
}
