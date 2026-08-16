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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class HibernateExternalPrincipalBindingServiceTest {

  private static final Instant FIRST_TIME = Instant.parse("2026-08-16T10:00:00Z");
  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

  @Test
  void createsResolvesAndRefreshesOneStableExternalPrincipal() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      AtomicInteger ids = new AtomicInteger();
      HibernateExternalPrincipalBindingService service =
          service(sessionFactory, FIRST_TIME, identity -> "principal-" + ids.incrementAndGet());
      ExternalPrincipalIdentity alice =
          new ExternalPrincipalIdentity(
              "https://issuer.example.test/realms/taxonomy",
              "subject-alice",
              "Alice",
              Map.of("preferred_username", "alice"));

      ExternalPrincipalBindingResult created =
          service.resolve(alice, ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);
      assertEquals("principal-1", created.principalId());
      assertEquals(ExternalPrincipalBindingOutcome.CREATED, created.outcome());
      assertEquals("Alice", created.displayName());
      assertEquals(1, created.securityVersion());

      ExternalPrincipalBindingResult resolved =
          service.resolve(alice, ExternalPrincipalProvisioningPolicy.EXISTING_ONLY);
      assertEquals(created.principalId(), resolved.principalId());
      assertEquals(ExternalPrincipalBindingOutcome.RESOLVED, resolved.outcome());
      assertEquals(1, resolved.securityVersion());
      assertEquals(1, ids.get());

      HibernateExternalPrincipalBindingService later =
          service(
              sessionFactory,
              FIRST_TIME.plusSeconds(60),
              identity -> "unexpected-new-principal");
      ExternalPrincipalBindingResult updated =
          later.resolve(
              ExternalPrincipalIdentity.of(
                  alice.issuer(), alice.subject(), "Alice Renamed"),
              ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);
      assertEquals(created.principalId(), updated.principalId());
      assertEquals(ExternalPrincipalBindingOutcome.UPDATED_PROFILE, updated.outcome());
      assertEquals("Alice Renamed", updated.displayName());
      assertEquals(2, updated.securityVersion());

      sessionFactory.inTransaction(
          session -> {
            SecurityPrincipalEntity entity =
                session.find(SecurityPrincipalEntity.class, created.principalId());
            assertEquals(SecurityPrincipalType.EXTERNAL, entity.getPrincipalType());
            assertEquals(SecurityPrincipalStatus.ACTIVE, entity.getStatus());
            assertEquals(alice.issuer(), entity.getExternalIssuer());
            assertEquals(alice.subject(), entity.getExternalSubject());
            assertNull(entity.getLoginName());
            assertEquals("Alice Renamed", entity.getDisplayName());
            assertEquals(FIRST_TIME, entity.getCreatedAt());
            assertEquals(FIRST_TIME.plusSeconds(60), entity.getUpdatedAt());
            assertEquals(2, entity.getSecurityVersion());
          });
    }
  }

  @Test
  void missingAndInactiveBindingsFailClosedWithoutProvisioningByName() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      HibernateExternalPrincipalBindingService service =
          service(sessionFactory, FIRST_TIME, identity -> "principal-alice");
      ExternalPrincipalIdentity alice =
          ExternalPrincipalIdentity.of(
              "https://issuer.example.test", "subject-alice", "Alice");

      SecurityAuthenticationException missing =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.resolve(
                      alice, ExternalPrincipalProvisioningPolicy.EXISTING_ONLY));
      assertEquals(SecurityAuthenticationReason.INVALID_CREDENTIALS, missing.reason());

      ExternalPrincipalBindingResult created =
          service.resolve(alice, ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);
      sessionFactory.inTransaction(
          session ->
              session
                  .find(SecurityPrincipalEntity.class, created.principalId())
                  .setStatus(SecurityPrincipalStatus.DISABLED));

      SecurityAuthenticationException disabled =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.resolve(
                      alice, ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING));
      assertEquals(SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE, disabled.reason());
    }
  }

  @Test
  void issuerAndSubjectAreTheOnlyBindingKey() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      AtomicInteger ids = new AtomicInteger();
      HibernateExternalPrincipalBindingService service =
          service(
              provider.getSessionFactory(),
              FIRST_TIME,
              identity -> "principal-" + ids.incrementAndGet());

      ExternalPrincipalBindingResult first =
          service.resolve(
              ExternalPrincipalIdentity.of(
                  "https://issuer-a.example.test", "same-subject", "Same Display Name"),
              ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);
      ExternalPrincipalBindingResult second =
          service.resolve(
              ExternalPrincipalIdentity.of(
                  "https://issuer-b.example.test", "same-subject", "Same Display Name"),
              ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);

      assertNotEquals(first.principalId(), second.principalId());
      assertEquals(2, ids.get());
    }
  }

  @Test
  void trustedGeneratorCannotReuseAnExistingPrincipalId() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateExternalPrincipalBindingService service =
          service(provider.getSessionFactory(), FIRST_TIME, identity -> "fixed-principal");
      service.resolve(
          ExternalPrincipalIdentity.of(
              "https://issuer-a.example.test", "subject-a", "Alice"),
          ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);

      SecurityPolicyConfigurationException duplicate =
          assertThrows(
              SecurityPolicyConfigurationException.class,
              () ->
                  service.resolve(
                      ExternalPrincipalIdentity.of(
                          "https://issuer-b.example.test", "subject-b", "Bob"),
                      ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING));
      assertTrue(duplicate.getMessage().contains("already bound"));
    }
  }

  @Test
  void bindingBuildsAnExplicitImmutableExternalGitContext() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateExternalPrincipalBindingService service =
          service(provider.getSessionFactory(), FIRST_TIME, identity -> "principal-alice");
      ExternalPrincipalBindingResult binding =
          service.resolve(
              ExternalPrincipalIdentity.of(
                  "https://issuer.example.test", "subject-alice", "Alice"),
              ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING);
      LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
      attributes.put("tenant", "taxonomy");

      AuthenticatedGitAccess access =
          binding.toAuthenticatedGitAccess(
              Set.of("organization-1"),
              "oidc_jwt",
              SecurityAuthenticationTrace.withoutRemoteAddress(
                  "session-1", "correlation-1"),
              attributes);
      attributes.put("late", "must-not-appear");

      assertEquals("principal-alice", access.context().principalId());
      assertEquals(Set.of("organization-1"), access.context().groupIds());
      assertEquals("oidc_jwt", access.context().authenticationMethod());
      assertEquals("session-1", access.context().sessionId());
      assertEquals("correlation-1", access.context().correlationId());
      assertEquals(Map.of("tenant", "taxonomy"), access.context().attributes());
      assertEquals(SecurityCredentialKind.EXTERNAL, access.credentialKind());
      assertEquals("principal-alice", access.credentialId());
      assertTrue(access.carries(GitRepositoryPermission.ADMINISTER));
    }
  }

  @Test
  void rejectsUntrustedOrUnboundedIdentityAndGeneratedIdInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalPrincipalIdentity.of("relative/issuer", "subject", "Display"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalPrincipalIdentity.of("https://issuer.test#fragment", "subject", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalPrincipalIdentity.of("https://issuer.test", " subject ", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExternalPrincipalIdentity(
                "https://issuer.test", "subject", null, null));

    Map<String, String> tooMany = new LinkedHashMap<>();
    for (int index = 0; index < 33; index++) {
      tooMany.put("key-" + index, "value");
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExternalPrincipalIdentity(
                "https://issuer.test", "subject", null, tooMany));

    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateExternalPrincipalBindingService invalidIdService =
          service(provider.getSessionFactory(), FIRST_TIME, identity -> " invalid ");
      assertThrows(
          SecurityPolicyConfigurationException.class,
          () ->
              invalidIdService.resolve(
                  ExternalPrincipalIdentity.of(
                      "https://issuer.test", "subject", "Display"),
                  ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING));
    }
  }

  private static HibernateExternalPrincipalBindingService service(
      SessionFactory sessionFactory,
      Instant time,
      ExternalPrincipalIdGenerator principalIdGenerator) {
    return new HibernateExternalPrincipalBindingService(
        sessionFactory,
        Clock.fixed(time, ZoneOffset.UTC),
        principalIdGenerator);
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:external-principal-binding-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties, SecurityEntities.annotatedClasses());
  }
}
