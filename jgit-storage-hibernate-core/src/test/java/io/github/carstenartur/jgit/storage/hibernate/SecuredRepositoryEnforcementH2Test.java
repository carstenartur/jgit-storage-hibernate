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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.BatchRefUpdate;
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
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecuredRepositoryEnforcementH2Test {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName REPOSITORY = new RepositoryName("secured-workflows");

  @Test
  void openFailsClosedBeforeLookupAndBindsTheExplicitContext() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("open")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      initializeRepository(sessionFactory);
      MutablePolicy policy = new MutablePolicy();
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);

      RepositoryAccessDeniedException discoverDenied =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () -> factory.open(new RepositoryName("not-visible"), "alice"));
      assertEquals(RepositoryAccessOperation.DISCOVER, discoverDenied.request().operation());
      assertEquals(List.of(RepositoryAccessOperation.DISCOVER), policy.operations());

      policy.allow(RepositoryAccessOperation.DISCOVER);
      RepositoryAccessDeniedException readDenied =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () -> factory.open(REPOSITORY, "alice"));
      assertEquals(RepositoryAccessOperation.READ, readDenied.request().operation());

      policy.allow(RepositoryAccessOperation.READ);
      try (AuthorizedRepositorySession<String> session = factory.open(REPOSITORY, "alice")) {
        assertEquals(REPOSITORY, session.repositoryName());
        assertEquals("alice", session.accessContext());
        assertNotNull(session.repository().exactRef("refs/heads/main"));
        session.require(
            RepositoryAccessRequest.repository(REPOSITORY, RepositoryAccessOperation.READ));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                session.require(
                    RepositoryAccessRequest.repository(
                        new RepositoryName("other"), RepositoryAccessOperation.READ)));
      }
    }
  }

  @Test
  void singleAndBatchRefMutationsAreRecheckedAtPublication() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("refs")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      ObjectId initial = initializeRepository(sessionFactory);
      MutablePolicy policy = new MutablePolicy();
      policy.allow(RepositoryAccessOperation.DISCOVER, RepositoryAccessOperation.READ);
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);

      try (AuthorizedRepositorySession<String> session = factory.open(REPOSITORY, "alice")) {
        Repository repository = session.repository();
        RefUpdate create = repository.updateRef("refs/heads/topic");
        create.setExpectedOldObjectId(ObjectId.zeroId());
        create.setNewObjectId(initial);
        assertEquals(RefUpdate.Result.REJECTED_OTHER_REASON, create.update());
        assertNull(repository.exactRef("refs/heads/topic"));

        policy.allow(RepositoryAccessOperation.CREATE_REF);
        create = repository.updateRef("refs/heads/topic");
        create.setExpectedOldObjectId(ObjectId.zeroId());
        create.setNewObjectId(initial);
        assertEquals(RefUpdate.Result.NEW, create.update());

        ObjectId child = insertCommit(repository, "child", initial);
        policy.allow(RepositoryAccessOperation.UPDATE_REF);
        RefUpdate update = repository.updateRef("refs/heads/topic");
        update.setExpectedOldObjectId(initial);
        update.setNewObjectId(child);
        assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());

        ObjectId unrelated = insertCommit(repository, "unrelated", null);
        RefUpdate force = repository.updateRef("refs/heads/topic");
        force.setExpectedOldObjectId(child);
        force.setNewObjectId(unrelated);
        force.setForceUpdate(true);
        assertEquals(RefUpdate.Result.REJECTED_OTHER_REASON, force.update());
        assertEquals(child, repository.exactRef("refs/heads/topic").getObjectId());

        policy.allow(RepositoryAccessOperation.FORCE_UPDATE);
        force = repository.updateRef("refs/heads/topic");
        force.setExpectedOldObjectId(child);
        force.setNewObjectId(unrelated);
        force.setForceUpdate(true);
        assertEquals(RefUpdate.Result.FORCED, force.update());

        policy.denyRef("refs/heads/batch-blocked");
        ReceiveCommand first =
            new ReceiveCommand(
                ObjectId.zeroId(), initial, "refs/heads/batch-allowed");
        ReceiveCommand second =
            new ReceiveCommand(
                ObjectId.zeroId(), initial, "refs/heads/batch-blocked");
        BatchRefUpdate batch = repository.getRefDatabase().newBatchUpdate();
        batch.addCommand(first, second);
        try (RevWalk walk = new RevWalk(repository)) {
          batch.execute(walk, NullProgressMonitor.INSTANCE);
        }
        assertFalse(first.getResult() == ReceiveCommand.Result.OK);
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, second.getResult());
        assertNull(repository.exactRef("refs/heads/batch-allowed"));
        assertNull(repository.exactRef("refs/heads/batch-blocked"));

        RefUpdate delete = repository.updateRef("refs/heads/topic");
        assertEquals(RefUpdate.Result.REJECTED_OTHER_REASON, delete.delete());
        policy.allow(RepositoryAccessOperation.DELETE_REF);
        delete = repository.updateRef("refs/heads/topic");
        assertTrue(
            Set.of(RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD)
                .contains(delete.delete()));
        assertNull(repository.exactRef("refs/heads/topic"));
      }
    }
  }

  @Test
  void repositoryDeletionIsCheckedBeforeAndAfterTheRepositoryLock() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("delete")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      initializeRepository(sessionFactory);
      MutablePolicy policy = new MutablePolicy();
      policy.allow(RepositoryAccessOperation.DELETE_REPOSITORY);
      policy.denyDeleteCheck(2);
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);

      assertThrows(
          RepositoryAccessDeniedException.class,
          () -> factory.deleteRepository(REPOSITORY, "alice"));
      try (HibernateGitStorage storage =
          new DefaultHibernateRepositoryFactory(sessionFactory).open(REPOSITORY)) {
        assertNotNull(storage.repository().exactRef("refs/heads/main"));
      }

      policy.denyDeleteCheck(-1);
      RepositoryDeletionResult deleted = factory.deleteRepository(REPOSITORY, "alice");
      assertTrue(deleted.deletedAnything());
      assertTrue(policy.deleteChecks() >= 4);
    }
  }

  @Test
  void accessRequestAndDenialEvidenceValidateTheirScope() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RepositoryAccessRequest.repository(
                REPOSITORY, RepositoryAccessOperation.CREATE_REF));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RepositoryAccessRequest.ref(
                REPOSITORY,
                RepositoryAccessOperation.READ,
                "refs/heads/main",
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryAccessDeniedException(
                RepositoryAccessRequest.repository(
                    REPOSITORY, RepositoryAccessOperation.READ),
                "DENIED",
                null,
                -1));
  }

  private static ObjectId initializeRepository(SessionFactory sessionFactory) throws Exception {
    try (HibernateGitStorage storage =
        new DefaultHibernateRepositoryFactory(sessionFactory).open(REPOSITORY)) {
      Repository repository = storage.repository();
      ObjectId initial = insertCommit(repository, "initial", null);
      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(initial);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return initial;
    }
  }

  private static ObjectId insertCommit(
      Repository repository, String message, ObjectId parent) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, message.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("data.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent actor = new PersonIdent("Security Test", "security@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:secured-"
            + purpose
            + "-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static final class MutablePolicy implements RepositoryAccessPolicy<String> {
    private final Set<RepositoryAccessOperation> allowed =
        EnumSet.noneOf(RepositoryAccessOperation.class);
    private final Set<String> deniedRefs = new HashSet<>();
    private final List<RepositoryAccessRequest> requests = new ArrayList<>();
    private int deleteChecks;
    private int deniedDeleteCheck = -1;

    void allow(RepositoryAccessOperation... operations) {
      allowed.addAll(List.of(operations));
    }

    void denyRef(String refName) {
      deniedRefs.add(refName);
    }

    void denyDeleteCheck(int check) {
      deniedDeleteCheck = check;
    }

    int deleteChecks() {
      return deleteChecks;
    }

    List<RepositoryAccessOperation> operations() {
      return requests.stream().map(RepositoryAccessRequest::operation).toList();
    }

    @Override
    public void require(String context, RepositoryAccessRequest request) {
      assertEquals("alice", context);
      requests.add(request);
      if (request.operation() == RepositoryAccessOperation.DELETE_REPOSITORY) {
        deleteChecks++;
        if (deleteChecks == deniedDeleteCheck) {
          deny(request, "REVOKED_DURING_DELETE");
        }
      }
      if (!allowed.contains(request.operation())
          || request.refName() != null && deniedRefs.contains(request.refName())) {
        deny(request, "DENIED_BY_TEST_POLICY");
      }
    }

    private static void deny(RepositoryAccessRequest request, String reason) {
      throw new RepositoryAccessDeniedException(request, reason, "test-policy", 7);
    }
  }
}
