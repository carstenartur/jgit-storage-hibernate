/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.Test;

class HibernateReceivePackH2Test {

  private static final String SOURCE_REF = "refs/heads/source";
  private static final String MAIN_REF = "refs/heads/main";
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Test
  void commitsPackAndRefMutationsWithOneRepositoryLock() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository server =
            HibernateRepository.create(provider.getSessionFactory(), "transactional-push");
        InMemoryRepository source = memoryRepository("transactional-push-source")) {
      server.create(true);
      ObjectId tip = writeHistory(source, 8, 8 * 1024, 0x12345678);
      StorageOperationMetrics before = server.getStorageOperationMetrics();

      TestProtocol<Object> protocol = protocol(false);
      Transport.register(protocol);
      try {
        URIish uri = protocol.register(new Object(), server);
        push(source, uri, tip);
      } finally {
        Transport.unregister(protocol);
      }

      StorageOperationMetrics delta = server.getStorageOperationMetrics().minus(before);
      assertEquals(0, delta.transactionsRolledBack());
      assertEquals(delta.transactionsStarted(), delta.transactionsCommitted());
      assertTrue(delta.transactionsStarted() <= 3, "advertisement plus one mutation transaction");
      assertEquals(1, delta.repositoryLocksAcquired());

      assertNotNull(server.exactRef(MAIN_REF));
      assertEquals(tip, server.exactRef(MAIN_REF).getObjectId());
      assertEquals(Constants.OBJ_COMMIT, server.open(tip).getType());

      server.close();
      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), "transactional-push")) {
        assertEquals(tip, reopened.exactRef(MAIN_REF).getObjectId());
        assertEquals(Constants.OBJ_COMMIT, reopened.open(tip).getType());
      }
    }
  }

  @Test
  void rollsBackPackMutationAndInvalidatesCachesWhenPreReceiveFails() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository server =
            HibernateRepository.create(provider.getSessionFactory(), "transactional-rollback");
        InMemoryRepository source = memoryRepository("transactional-rollback-source")) {
      server.create(true);
      ObjectId tip = writeHistory(source, 4, 4 * 1024, 0x24681357);
      long packsBefore = packRowCount(provider, "transactional-rollback");
      StorageOperationMetrics before = server.getStorageOperationMetrics();

      TestProtocol<Object> protocol = protocol(true);
      Transport.register(protocol);
      try {
        URIish uri = protocol.register(new Object(), server);
        assertThrows(TransportException.class, () -> push(source, uri, tip));
      } finally {
        Transport.unregister(protocol);
      }

      StorageOperationMetrics delta = server.getStorageOperationMetrics().minus(before);
      assertEquals(1, delta.transactionsRolledBack());
      assertEquals(1, delta.repositoryLocksAcquired());
      assertEquals(packsBefore, packRowCount(provider, "transactional-rollback"));
      assertNull(server.exactRef(MAIN_REF));
      assertThrows(MissingObjectException.class, () -> server.open(tip));
    }
  }

  private static TestProtocol<Object> protocol(boolean failBeforeCommands) {
    return new TestProtocol<>(
        (Object request, Repository repository) -> new UploadPack(repository),
        (Object request, Repository repository) -> {
          ReceivePack receive = ((HibernateRepository) repository).newReceivePack();
          if (failBeforeCommands) {
            receive.setPreReceiveHook(
                (pack, commands) -> {
                  throw new IllegalStateException("intentional pre-receive failure");
                });
          }
          return receive;
        });
  }

  private static void push(InMemoryRepository source, URIish uri, ObjectId tip) throws Exception {
    RemoteRefUpdate update =
        new RemoteRefUpdate(source, SOURCE_REF, MAIN_REF, false, null, ObjectId.zeroId());
    try (Transport transport = Transport.open(source, uri)) {
      transport.push(NullProgressMonitor.INSTANCE, List.of(update));
    }
    if (update.getStatus() != RemoteRefUpdate.Status.OK) {
      throw new TransportException(uri, "Unexpected push status " + update.getStatus());
    }
    assertEquals(tip, update.getNewObjectId());
  }

  private static ObjectId writeHistory(
      InMemoryRepository repository, int commits, int payloadSize, int seed) throws Exception {
    ObjectId parent = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 0; index < commits; index++) {
        byte[] payload = new byte[payloadSize];
        new Random((((long) seed) << 32) ^ index).nextBytes(payload);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);
        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        Date timestamp = Date.from(Instant.ofEpochSecond(1_700_000_000L + index));
        PersonIdent identity = new PersonIdent("Receive test", "test@example.invalid", timestamp, UTC);
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (parent != null) {
          commit.setParentId(parent);
        }
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Commit " + index);
        parent = inserter.insert(commit);
      }
      inserter.flush();
    }

    RefUpdate update = repository.updateRef(SOURCE_REF);
    update.setNewObjectId(parent);
    assertEquals(RefUpdate.Result.NEW, update.update());
    return parent;
  }

  private static InMemoryRepository memoryRepository(String name) throws Exception {
    InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription(name));
    repository.create(true);
    return repository;
  }

  private static long packRowCount(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (var session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT count(p) FROM GitPackEntity p WHERE p.repositoryName = :repo",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:transactional-receive-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
