/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.AuthorizedRepositorySession;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecuredRepositoryAccessAuditH2Test {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName REPOSITORY = new RepositoryName("audited-repository");
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of(), "oidc", "session-audit", "correlation-audit", Map.of());

  @Test
  void securedOpenAndDirectRefMutationsAppendPersistentAuditEvents() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      ObjectId initial = initializeRepository(sessionFactory);
      persistPrincipalAndGrants(sessionFactory);

      HibernateSecurityAccessAuditService audit =
          new HibernateSecurityAccessAuditService(sessionFactory);
      HibernateSecurityRepositoryAccessPolicy policy =
          new HibernateSecurityRepositoryAccessPolicy(sessionFactory, audit);
      SecuredHibernateRepositoryFactory<GitAccessContext> secured =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);

      try (AuthorizedRepositorySession<GitAccessContext> session =
          secured.open(REPOSITORY, ALICE)) {
        Repository repository = session.repository();

        RefUpdate create = repository.updateRef("refs/heads/topic");
        create.setExpectedOldObjectId(ObjectId.zeroId());
        create.setNewObjectId(initial);
        assertEquals(RefUpdate.Result.NEW, create.update());

        ObjectId unrelated = insertCommit(repository, "unrelated", null);
        RefUpdate force = repository.updateRef("refs/heads/topic");
        force.setExpectedOldObjectId(initial);
        force.setNewObjectId(unrelated);
        force.setForceUpdate(true);
        assertEquals(RefUpdate.Result.REJECTED_OTHER_REASON, force.update());
      }

      List<SecurityAccessAuditEvent> events =
          audit.findByCorrelationId("correlation-audit", 20);
      assertEquals(4, events.size());
      assertTrue(has(events, RepositoryAccessOperation.DISCOVER, SecurityAuditOutcome.ALLOWED));
      assertTrue(has(events, RepositoryAccessOperation.READ, SecurityAuditOutcome.ALLOWED));
      assertTrue(has(events, RepositoryAccessOperation.CREATE_REF, SecurityAuditOutcome.ALLOWED));
      assertTrue(has(events, RepositoryAccessOperation.FORCE_UPDATE, SecurityAuditOutcome.DENIED));
    }
  }

  private static boolean has(
      List<SecurityAccessAuditEvent> events,
      RepositoryAccessOperation operation,
      SecurityAuditOutcome outcome) {
    return events.stream()
        .map(SecurityAccessAuditEvent::record)
        .anyMatch(record -> record.operation() == operation && record.outcome() == outcome);
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
      PersonIdent actor = new PersonIdent("Audit Test", "audit@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static void persistPrincipalAndGrants(SessionFactory sessionFactory) {
    Instant now = Instant.parse("2026-08-13T00:00:00Z");
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId("alice");
    principal.setPrincipalType(SecurityPrincipalType.EXTERNAL);
    principal.setLoginName("alice@example.test");
    principal.setDisplayName("Alice");
    principal.setExternalIssuer("https://issuer.example.test");
    principal.setExternalSubject("subject-alice");
    principal.setStatus(SecurityPrincipalStatus.ACTIVE);
    principal.setCreatedAt(now);
    principal.setUpdatedAt(now);
    principal.setSecurityVersion(1);

    sessionFactory.inTransaction(
        session -> {
          session.persist(principal);
          session.persist(grant("discover", GitRepositoryPermission.DISCOVER, now, 2));
          session.persist(grant("read", GitRepositoryPermission.READ, now, 3));
          session.persist(grant("create", GitRepositoryPermission.CREATE_REF, now, 4));
        });
  }

  private static SecurityRepositoryGrantEntity grant(
      String id, GitRepositoryPermission permission, Instant createdAt, long version) {
    SecurityRepositoryGrantEntity grant = new SecurityRepositoryGrantEntity();
    grant.setGrantId("allow-" + id);
    grant.setRepositoryName(REPOSITORY.value());
    grant.setSubjectType(SecuritySubjectType.PRINCIPAL);
    grant.setSubjectId("alice");
    grant.setPermission(permission);
    grant.setEffect(SecurityEffect.ALLOW);
    grant.setCreatedAt(createdAt);
    grant.setCreatedBy("system");
    grant.setSecurityVersion(version);
    return grant;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:secured-audit-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(
        properties, SecurityEntities.annotatedClasses());
  }
}
