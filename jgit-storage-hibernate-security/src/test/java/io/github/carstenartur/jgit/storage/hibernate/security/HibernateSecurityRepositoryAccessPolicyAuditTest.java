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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class HibernateSecurityRepositoryAccessPolicyAuditTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName REPOSITORY = new RepositoryName("workflows");
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of("forged-client-group"), "oidc", "session", "correlation", Map.of());

  @Test
  void persistsAllowedDeniedAndInactivePrincipalDecisions() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipalAndReadGrant(sessionFactory);
      HibernateSecurityAccessAuditService audit =
          new HibernateSecurityAccessAuditService(sessionFactory);
      HibernateSecurityRepositoryAccessPolicy policy =
          new HibernateSecurityRepositoryAccessPolicy(sessionFactory, audit);

      policy.require(
          ALICE,
          RepositoryAccessRequest.repository(
              REPOSITORY, RepositoryAccessOperation.READ));
      assertThrows(
          RepositoryAccessDeniedException.class,
          () ->
              policy.require(
                  ALICE,
                  RepositoryAccessRequest.ref(
                      REPOSITORY,
                      RepositoryAccessOperation.UPDATE_REF,
                      "refs/heads/main",
                      null,
                      null)));

      sessionFactory.inTransaction(
          session -> {
            SecurityPrincipalEntity principal =
                session.find(SecurityPrincipalEntity.class, "alice");
            principal.setStatus(SecurityPrincipalStatus.DISABLED);
          });
      assertThrows(
          RepositoryAccessDeniedException.class,
          () ->
              policy.require(
                  ALICE,
                  RepositoryAccessRequest.repository(
                      REPOSITORY, RepositoryAccessOperation.READ)));

      List<SecurityAccessAuditEvent> events =
          audit.findByCorrelationId("correlation", 10);
      assertEquals(3, events.size());
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event.record().outcome() == SecurityAuditOutcome.ALLOWED
                          && event.record().reasonCode().equals("GRANT_ALLOWED")));
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event.record().outcome() == SecurityAuditOutcome.DENIED
                          && event.record().reasonCode().equals("NO_MATCHING_GRANT")));
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event.record().outcome() == SecurityAuditOutcome.DENIED
                          && event.record().reasonCode().equals("PRINCIPAL_NOT_ACTIVE")));
    }
  }

  private static void persistPrincipalAndReadGrant(SessionFactory sessionFactory) {
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

    SecurityRepositoryGrantEntity grant = new SecurityRepositoryGrantEntity();
    grant.setGrantId("allow-read");
    grant.setRepositoryName(REPOSITORY.value());
    grant.setSubjectType(SecuritySubjectType.PRINCIPAL);
    grant.setSubjectId("alice");
    grant.setPermission(GitRepositoryPermission.READ);
    grant.setEffect(SecurityEffect.ALLOW);
    grant.setCreatedAt(now);
    grant.setCreatedBy("system");
    grant.setSecurityVersion(2);

    sessionFactory.inTransaction(
        session -> {
          session.persist(principal);
          session.persist(grant);
        });
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:security-policy-audit-"
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
