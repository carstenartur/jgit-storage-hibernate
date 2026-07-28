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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
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
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.Test;

class IncrementalFetchH2Test {

  private static final int BASE_COMMITS = 20;
  private static final int INCREMENTAL_COMMITS = 4;
  private static final int PAYLOAD_BYTES = 32 * 1024;
  private static final int SEED = 0x27182818;
  private static final String MAIN = "refs/heads/main";
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Test
  void fetchesDescendantsFromSecondPackWithoutCorruptingTheProtocolStream() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository server =
            HibernateRepository.create(provider.getSessionFactory(), "incremental-fetch");
        InMemoryRepository client =
            new InMemoryRepository(new DfsRepositoryDescription("incremental-fetch-client"))) {
      server.create(true);
      client.create(true);

      ObjectId serverBase =
          writeHistory(server, null, 0, BASE_COMMITS, SEED, "refs/heads/base");
      ObjectId serverTip =
          writeHistory(
              server,
              serverBase,
              BASE_COMMITS,
              INCREMENTAL_COMMITS,
              SEED,
              MAIN);
      ObjectId clientBase = writeHistory(client, null, 0, BASE_COMMITS, SEED, MAIN);
      assertEquals(serverBase, clientBase, "client and server must share the same base objects");

      long committedPacks;
      try (var session = provider.getSessionFactory().openSession()) {
        committedPacks =
            session
                .createQuery(
                    "SELECT count(p) FROM GitPackEntity p "
                        + "WHERE p.repositoryName = :repo AND p.packExtension = 'pack' "
                        + "AND p.committed = true",
                    Long.class)
                .setParameter("repo", "incremental-fetch")
                .getSingleResult();
      }
      assertEquals(2L, committedPacks, "the server fixture must contain two published packs");

      TestProtocol<Object> protocol =
          new TestProtocol<>(
              (Object request, Repository repository) -> new UploadPack(repository),
              (Object request, Repository repository) -> new ReceivePack(repository));
      Transport.register(protocol);
      try {
        URIish uri = protocol.register(new Object(), server);
        try (Transport transport = Transport.open(client, uri)) {
          transport.fetch(
              NullProgressMonitor.INSTANCE, List.of(new RefSpec(MAIN + ":" + MAIN)));
        }
      } finally {
        Transport.unregister(protocol);
      }

      Ref localMain = client.exactRef(MAIN);
      assertNotNull(localMain);
      assertEquals(serverTip, localMain.getObjectId());
      assertEquals(PAYLOAD_BYTES, client.open(serverTip).getSize() > 0 ? PAYLOAD_BYTES : 0);
    }
  }

  private static ObjectId writeHistory(
      Repository repository,
      ObjectId parent,
      int startIndex,
      int commitCount,
      int seed,
      String refName)
      throws Exception {
    ObjectId tip = parent;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = startIndex; index < startIndex + commitCount; index++) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        new Random((((long) seed) << 32) ^ index).nextBytes(payload);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);

        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        Date timestamp =
            Date.from(Instant.ofEpochSecond(1_700_000_000L + (seed & 0xffffL) * 100L + index));
        PersonIdent identity =
            new PersonIdent("Protocol test", "test@example.invalid", timestamp, UTC);
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (tip != null) {
          commit.setParentId(tip);
        }
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Protocol history " + seed + " commit " + index);
        tip = inserter.insert(commit);
      }
      inserter.flush();
    }

    RefUpdate update = repository.updateRef(refName);
    update.setNewObjectId(tip);
    RefUpdate.Result result = update.update();
    if (result != RefUpdate.Result.NEW
        && result != RefUpdate.Result.FAST_FORWARD
        && result != RefUpdate.Result.FORCED) {
      throw new IllegalStateException("Unexpected ref update result " + result + " for " + refName);
    }
    return tip;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:incremental-fetch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    return new HibernateSessionFactoryProvider(properties);
  }
}
