/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.Test;

/** Regression coverage for copy-as-is pack transfer after persisted pack metadata is reloaded. */
class UploadPackHsqldbRoundTripTest {

  private static final int BASE_COMMITS = 10;
  private static final int TIP_COMMITS = 2;
  private static final int PAYLOAD_BYTES = 32 * 1024;
  private static final int HISTORY_SEED = 0x27182818;
  private static final String MAIN = "refs/heads/main";
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Test
  void incrementallyFetchesStoredRepresentationsAfterPackListReload() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    TestProtocol<Object> protocol = null;
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository server =
            HibernateRepository.create(provider.getSessionFactory(), "upload-pack-server");
        InMemoryRepository client =
            new InMemoryRepository(new DfsRepositoryDescription("upload-pack-client"))) {
      server.create(true);
      client.create(true);

      ObjectId serverBase = writeHistory(server, null, 0, BASE_COMMITS, "refs/heads/base");
      ObjectId tip = writeHistory(server, serverBase, BASE_COMMITS, TIP_COMMITS, MAIN);
      ObjectId clientBase = writeHistory(client, null, 0, BASE_COMMITS, MAIN);
      assertEquals(serverBase, clientBase, "The prepared client must share the server base history");

      // Force the server to reconstruct DfsPackDescription from persisted database rows. The
      // protocol copy-as-is path needs the restored file sizes before it copies compressed ranges.
      server.getObjectDatabase().close();
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());

      protocol =
          new TestProtocol<>(
              (Object request, Repository repository) -> new UploadPack(repository),
              (Object request, Repository repository) -> new ReceivePack(repository));
      Transport.register(protocol);
      URIish uri = protocol.register(new Object(), server);

      try (Transport transport = Transport.open(client, uri)) {
        transport.fetch(NullProgressMonitor.INSTANCE, List.of(new RefSpec(MAIN + ":" + MAIN)));
      }

      Ref localMain = client.exactRef(MAIN);
      assertEquals(tip, localMain.getObjectId());
      try (ObjectReader reader = client.newObjectReader()) {
        assertTrue(reader.has(tip), "The fetched tip commit must be readable from the client");
      }
    } finally {
      if (protocol != null) {
        Transport.unregister(protocol);
      }
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    }
  }

  private static ObjectId writeHistory(
      Repository repository,
      ObjectId parent,
      int startIndex,
      int commitCount,
      String refName)
      throws Exception {
    ObjectId tip = parent;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = startIndex; index < startIndex + commitCount; index++) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        new Random((((long) HISTORY_SEED) << 32) ^ index).nextBytes(payload);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);

        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        Date timestamp =
            Date.from(
                Instant.ofEpochSecond(
                    1_700_000_000L + (HISTORY_SEED & 0xffffL) * 100L + index));
        PersonIdent identity =
            new PersonIdent("UploadPack test", "test@example.invalid", timestamp, UTC);
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (tip != null) {
          commit.setParentId(tip);
        }
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("UploadPack history commit " + index);
        tip = inserter.insert(commit);
      }
      inserter.flush();
    }

    RefUpdate update = repository.updateRef(refName);
    update.setNewObjectId(tip);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW
            || result == RefUpdate.Result.FAST_FORWARD
            || result == RefUpdate.Result.FORCED,
        () -> "Unexpected ref update result " + result + " for " + refName);
    return tip;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:hsqldb:mem:upload-pack-" + UUID.randomUUID());
    properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    return new HibernateSessionFactoryProvider(properties);
  }
}
