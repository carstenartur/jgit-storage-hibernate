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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessAuditEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class HibernateSecurityAccessAuditServiceTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName WORKFLOWS = new RepositoryName("workflows");
  private static final RepositoryName OTHER = new RepositoryName("other");
  private static final ObjectId OLD_ID =
      ObjectId.fromString("1111111111111111111111111111111111111111");
  private static final ObjectId NEW_ID =
      ObjectId.fromString("2222222222222222222222222222222222222222");
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of(), "oidc", "session-a", "correlation-a", Map.of());
  private static final GitAccessContext BOB =
      new GitAccessContext(
          "bob", Set.of(), "token", "session-b", "correlation-b", Map.of());

  @Test
  void persistsEveryFieldAndProvidesOnlyBoundedScopedQueries() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      Instant firstTime = Instant.parse("2026-08-13T00:00:00Z");
      Instant secondTime = firstTime.plusSeconds(1);
      Instant thirdTime = firstTime.plusSeconds(2);
      HibernateSecurityAccessAuditService first =
          service(provider, firstTime, "audit-1");
      HibernateSecurityAccessAuditService second =
          service(provider, secondTime, "audit-2");
      HibernateSecurityAccessAuditService third =
          service(provider, thirdTime, "audit-3");

      SecurityAccessAuditRecord allowed =
          SecurityAccessAuditRecord.decision(
              ALICE,
              RepositoryAccessRequest.repository(
                  WORKFLOWS, RepositoryAccessOperation.READ),
              new AuthorizationDecision(
                  true,
                  AuthorizationReason.GRANT_ALLOWED,
                  "grant-read",
                  4,
                  Set.of(GitRepositoryPermission.READ)));
      SecurityAccessAuditRecord denied =
          SecurityAccessAuditRecord.decision(
              ALICE,
              RepositoryAccessRequest.ref(
                  WORKFLOWS,
                  RepositoryAccessOperation.UPDATE_REF,
                  "refs/heads/main",
                  OLD_ID,
                  NEW_ID),
              new AuthorizationDecision(
                  false,
                  AuthorizationReason.PROTECTED_REF_DENY,
                  "protect-main",
                  7,
                  Set.of(GitRepositoryPermission.UPDATE_REF)));
      SecurityAccessAuditRecord failed =
          SecurityAccessAuditRecord.failed(
              BOB,
              RepositoryAccessRequest.repository(
                  OTHER, RepositoryAccessOperation.DISCOVER),
              new IllegalStateException("policy unavailable"));

      first.record(allowed);
      second.record(denied);
      third.record(failed);

      SecurityAccessAuditEvent persistedDenied =
          second.findByAuditId("audit-2").orElseThrow();
      assertEquals("audit-2", persistedDenied.auditId());
      assertEquals(secondTime, persistedDenied.occurredAt());
      assertEquals(denied, persistedDenied.record());
      assertFalse(second.findByAuditId("missing").isPresent());

      assertEquals(
          List.of("audit-2", "audit-1"),
          second.findByRepository(WORKFLOWS, 10).stream()
              .map(SecurityAccessAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2"),
          second.findByRepository(WORKFLOWS, 1).stream()
              .map(SecurityAccessAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2", "audit-1"),
          second.findByPrincipal("alice", 10).stream()
              .map(SecurityAccessAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2", "audit-1"),
          second.findByCorrelationId("correlation-a", 10).stream()
              .map(SecurityAccessAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-3"),
          third.findByRepository(OTHER, 10).stream()
              .map(SecurityAccessAuditEvent::auditId)
              .toList());
    }
  }

  @Test
  void immutableMappingDoesNotRewritePersistedEvidence() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateSecurityAccessAuditService service =
          service(
              provider,
              Instant.parse("2026-08-13T00:00:00Z"),
              "audit-immutable");
      service.record(
          SecurityAccessAuditRecord.decision(
              ALICE,
              RepositoryAccessRequest.repository(
                  WORKFLOWS, RepositoryAccessOperation.READ),
              new AuthorizationDecision(
                  true,
                  AuthorizationReason.GRANT_ALLOWED,
                  "grant-read",
                  4,
                  Set.of(GitRepositoryPermission.READ))));

      provider
          .getSessionFactory()
          .inTransaction(
              session -> {
                SecurityAccessAuditEntity entity =
                    session.find(SecurityAccessAuditEntity.class, "audit-immutable");
                entity.setReasonCode("tampered");
                entity.setEvidenceId("tampered");
              });

      SecurityAccessAuditRecord persisted =
          service.findByAuditId("audit-immutable").orElseThrow().record();
      assertEquals(AuthorizationReason.GRANT_ALLOWED.name(), persisted.reasonCode());
      assertEquals("grant-read", persisted.evidenceId());
    }
  }

  @Test
  void rejectsUnboundedOrUnscopedQueriesAndGeneratedIds() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateSecurityAccessAuditService service =
          service(provider, Instant.parse("2026-08-13T00:00:00Z"), "audit-1");

      assertThrows(
          IllegalArgumentException.class,
          () -> service.findByRepository(WORKFLOWS, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.findByRepository(WORKFLOWS, 1001));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.findByPrincipal(" ", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.findByCorrelationId(" ", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.findByAuditId(" "));

      HibernateSecurityAccessAuditService invalidIdService =
          new HibernateSecurityAccessAuditService(
              provider.getSessionFactory(),
              Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC),
              () -> " ");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              invalidIdService.record(
                  SecurityAccessAuditRecord.decision(
                      ALICE,
                      RepositoryAccessRequest.repository(
                          WORKFLOWS, RepositoryAccessOperation.READ),
                      new AuthorizationDecision(
                          true,
                          AuthorizationReason.GRANT_ALLOWED,
                          "grant-read",
                          1,
                          Set.of(GitRepositoryPermission.READ)))));
    }
  }

  private static HibernateSecurityAccessAuditService service(
      HibernateSessionFactoryProvider provider, Instant time, String auditId) {
    return new HibernateSecurityAccessAuditService(
        provider.getSessionFactory(),
        Clock.fixed(time, ZoneOffset.UTC),
        () -> auditId);
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:security-audit-"
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
