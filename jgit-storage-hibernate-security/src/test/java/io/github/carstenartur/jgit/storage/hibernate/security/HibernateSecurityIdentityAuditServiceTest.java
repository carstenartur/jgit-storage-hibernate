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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HibernateSecurityIdentityAuditServiceTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final Instant TIME = Instant.parse("2026-08-16T01:02:03Z");
  private static final GitAccessContext ACTOR =
      new GitAccessContext(
          "admin", Set.of(), "oidc", "session", "correlation", Map.of());

  @Test
  void persistsAndQueriesBoundedCredentialLifecycleEvidence() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      AtomicInteger ids = new AtomicInteger();
      HibernateSecurityIdentityAuditService service =
          new HibernateSecurityIdentityAuditService(
              provider.getSessionFactory(),
              Clock.fixed(TIME, ZoneOffset.UTC),
              () -> "audit-" + ids.incrementAndGet());

      SecurityManagementRequest setPassword =
          SecurityManagementRequest.password(
              ACTOR, SecurityManagementOperation.SET_PASSWORD, "alice");
      SecurityManagementRequest issueToken =
          SecurityManagementRequest.issueToken(ACTOR, "alice");
      SecurityIdentityAuditRecord passwordRecord =
          SecurityIdentityAuditRecord.management(
              setPassword,
              SecurityCredentialKind.PASSWORD,
              null,
              SecurityAuditOutcome.ALLOWED,
              "PASSWORD_SET");
      SecurityIdentityAuditRecord tokenRecord =
          SecurityIdentityAuditRecord.management(
              issueToken,
              SecurityCredentialKind.ACCESS_TOKEN,
              "token-1",
              SecurityAuditOutcome.ALLOWED,
              "TOKEN_ISSUED");

      service.record(passwordRecord);
      SecurityIdentityAuditEvent inTransaction =
          provider
              .getSessionFactory()
              .fromTransaction(session -> service.record(session, tokenRecord));

      assertEquals("audit-2", inTransaction.auditId());
      assertEquals(TIME, inTransaction.occurredAt());
      assertEquals(tokenRecord, inTransaction.record());
      assertEquals(passwordRecord, service.findByAuditId("audit-1").orElseThrow().record());
      assertFalse(service.findByAuditId("missing").isPresent());
      assertEquals(
          List.of("audit-2", "audit-1"),
          service.findBySubjectPrincipal("alice", 10).stream()
              .map(SecurityIdentityAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2", "audit-1"),
          service.findByActorPrincipal("admin", 10).stream()
              .map(SecurityIdentityAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2", "audit-1"),
          service.findByCorrelationId("correlation", 10).stream()
              .map(SecurityIdentityAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2"),
          service.findByCredentialId("token-1", 10).stream()
              .map(SecurityIdentityAuditEvent::auditId)
              .toList());
      assertEquals(
          List.of("audit-2"),
          service.findBySubjectPrincipal("alice", 1).stream()
              .map(SecurityIdentityAuditEvent::auditId)
              .toList());
    }
  }

  @Test
  void wrapsPersistenceFailuresAndRejectsUnboundedQueries() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateSecurityIdentityAuditService invalidIdService =
          new HibernateSecurityIdentityAuditService(
              provider.getSessionFactory(),
              Clock.fixed(TIME, ZoneOffset.UTC),
              () -> " ");
      SecurityIdentityAuditRecord record =
          SecurityIdentityAuditRecord.authentication(
              SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
              "alice",
              SecurityAuthenticationTrace.withoutRemoteAddress("session", "correlation"),
              SecurityCredentialKind.PASSWORD,
              "alice",
              SecurityAuditOutcome.ALLOWED,
              SecurityAuthenticationReason.PASSWORD_AUTHENTICATED);

      SecurityIdentityAuditPersistenceException invalidId =
          assertThrows(
              SecurityIdentityAuditPersistenceException.class,
              () -> invalidIdService.record(record));
      assertEquals(IllegalArgumentException.class, invalidId.getCause().getClass());
      assertThrows(NullPointerException.class, () -> invalidIdService.record(null));
      assertThrows(IllegalArgumentException.class, () -> invalidIdService.findByAuditId(" "));
      assertThrows(
          IllegalArgumentException.class,
          () -> invalidIdService.findBySubjectPrincipal(" ", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> invalidIdService.findByActorPrincipal(" ", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> invalidIdService.findByCorrelationId(" ", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> invalidIdService.findByCredentialId(" ", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> invalidIdService.findBySubjectPrincipal("alice", 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> invalidIdService.findBySubjectPrincipal("alice", 1001));
      assertThrows(
          NullPointerException.class,
          () ->
              new HibernateSecurityIdentityAuditService(
                  null, Clock.systemUTC(), () -> "audit"));
      assertThrows(
          NullPointerException.class,
          () ->
              new HibernateSecurityIdentityAuditService(
                  provider.getSessionFactory(), null, () -> "audit"));
      assertThrows(
          NullPointerException.class,
          () ->
              new HibernateSecurityIdentityAuditService(
                  provider.getSessionFactory(), Clock.systemUTC(), null));
    }
  }

  @Test
  void duplicateAuditIdIsReportedAsPersistenceFailure() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateSecurityIdentityAuditService service =
          new HibernateSecurityIdentityAuditService(
              provider.getSessionFactory(),
              Clock.fixed(TIME, ZoneOffset.UTC),
              () -> "duplicate");
      SecurityIdentityAuditRecord record =
          SecurityIdentityAuditRecord.authentication(
              SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
              "alice",
              SecurityAuthenticationTrace.withoutRemoteAddress("session", "correlation"),
              SecurityCredentialKind.ACCESS_TOKEN,
              "token-1",
              SecurityAuditOutcome.DENIED,
              SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED);

      service.record(record);
      assertThrows(SecurityIdentityAuditPersistenceException.class, () -> service.record(record));
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:security-identity-audit-"
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
