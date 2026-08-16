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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class HibernateCredentialScopedRepositoryAccessPolicyTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final Instant START = Instant.parse("2026-08-16T08:00:00Z");
  private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);
  private static final RepositoryAccessRequest READ_REQUEST =
      RepositoryAccessRequest.repository(
          new RepositoryName("demo"), RepositoryAccessOperation.READ);

  @Test
  void validTokenSurvivesLastUsedUpdatesAndRevocationStopsTheNextOperation() {
    try (HibernateSessionFactoryProvider provider = provider("revocation")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistToken(
          sessionFactory,
          token("token-1", "alice", "READ", 1, START.plusSeconds(600)));
      AuthenticatedGitAccess access =
          tokenAccess("token-1", "alice", 1, Set.of(GitRepositoryPermission.READ));
      AtomicInteger delegated = new AtomicInteger();
      List<SecurityAccessAuditRecord> audit = new ArrayList<>();
      HibernateCredentialScopedRepositoryAccessPolicy policy =
          new HibernateCredentialScopedRepositoryAccessPolicy(
              sessionFactory,
              (context, request) -> delegated.incrementAndGet(),
              audit::add,
              CLOCK);

      policy.require(access, READ_REQUEST);
      assertEquals(1, delegated.get());
      assertTrue(audit.isEmpty());

      sessionFactory.inTransaction(
          session -> {
            SecurityAccessTokenEntity entity =
                session.find(SecurityAccessTokenEntity.class, "token-1");
            entity.setLastUsedAt(START.plusSeconds(1));
          });
      policy.require(access, READ_REQUEST);
      assertEquals(2, delegated.get());

      sessionFactory.inTransaction(
          session -> {
            SecurityAccessTokenEntity entity =
                session.find(SecurityAccessTokenEntity.class, "token-1");
            entity.setRevokedAt(START.plusSeconds(2));
            entity.setSecurityVersion(2);
          });
      RepositoryAccessDeniedException revoked =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () -> policy.require(access, READ_REQUEST));
      assertEquals(SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED.name(), revoked.reasonCode());
      assertEquals("token-1", revoked.evidenceId());
      assertEquals(2, revoked.policyVersion());
      assertEquals(2, delegated.get());
      assertEquals(1, audit.size());
      assertEquals(SecurityAuditOutcome.DENIED, audit.getFirst().outcome());
      assertEquals(
          SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED.name(),
          audit.getFirst().reasonCode());
    }
  }

  @Test
  void expiryVersionScopePrincipalAndMissingTokenAllFailClosed() {
    try (HibernateSessionFactoryProvider provider = provider("negative")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistToken(sessionFactory, token("expired", "alice", "READ", 1, START));
      persistToken(
          sessionFactory,
          token("version-changed", "alice", "READ", 2, START.plusSeconds(600)));
      persistToken(
          sessionFactory,
          token("scope-changed", "alice", "UPDATE_REF", 1, START.plusSeconds(600)));
      persistToken(
          sessionFactory,
          token("principal-changed", "alice", "READ", 1, START.plusSeconds(600)));
      AtomicInteger delegated = new AtomicInteger();
      List<SecurityAccessAuditRecord> audit = new ArrayList<>();
      HibernateCredentialScopedRepositoryAccessPolicy policy =
          new HibernateCredentialScopedRepositoryAccessPolicy(
              sessionFactory,
              (context, request) -> delegated.incrementAndGet(),
              audit::add,
              CLOCK);

      assertDenied(
          policy,
          tokenAccess("expired", "alice", 1, Set.of(GitRepositoryPermission.READ)),
          SecurityAuthenticationReason.ACCESS_TOKEN_EXPIRED);
      assertDenied(
          policy,
          tokenAccess(
              "version-changed", "alice", 1, Set.of(GitRepositoryPermission.READ)),
          SecurityAuthenticationReason.INVALID_CREDENTIALS);
      assertDenied(
          policy,
          tokenAccess(
              "scope-changed", "alice", 1, Set.of(GitRepositoryPermission.READ)),
          SecurityAuthenticationReason.INVALID_CREDENTIALS);
      assertDenied(
          policy,
          tokenAccess(
              "principal-changed", "bob", 1, Set.of(GitRepositoryPermission.READ)),
          SecurityAuthenticationReason.INVALID_CREDENTIALS);
      assertDenied(
          policy,
          tokenAccess("missing", "alice", 1, Set.of(GitRepositoryPermission.READ)),
          SecurityAuthenticationReason.INVALID_CREDENTIALS);

      assertEquals(0, delegated.get());
      assertEquals(5, audit.size());
      assertTrue(audit.stream().allMatch(record -> record.outcome() == SecurityAuditOutcome.DENIED));
      assertFalse(audit.stream().anyMatch(record -> record.evidenceId() == null));
    }
  }

  @Test
  void passwordAndExternalCredentialsUseTheirApplicationSessionLifetime() {
    try (HibernateSessionFactoryProvider provider = provider("non-token")) {
      AtomicInteger delegated = new AtomicInteger();
      HibernateCredentialScopedRepositoryAccessPolicy policy =
          new HibernateCredentialScopedRepositoryAccessPolicy(
              provider.getSessionFactory(),
              (context, request) -> delegated.incrementAndGet(),
              SecurityAccessAuditRecorder.NONE,
              CLOCK);
      GitAccessContext context =
          new GitAccessContext(
              "alice", Set.of(), "password", "session", "correlation", Map.of());
      AuthenticatedGitAccess password =
          AuthenticatedGitAccess.unrestricted(
              context, SecurityCredentialKind.PASSWORD, "alice", 1);

      policy.require(password, READ_REQUEST);
      assertEquals(1, delegated.get());
    }
  }

  @Test
  void tokenStoreFailureIsAuditedAndNeverDelegates() {
    HibernateSessionFactoryProvider provider = provider("store-failure");
    SessionFactory sessionFactory = provider.getSessionFactory();
    List<SecurityAccessAuditRecord> audit = new ArrayList<>();
    AtomicInteger delegated = new AtomicInteger();
    HibernateCredentialScopedRepositoryAccessPolicy policy =
        new HibernateCredentialScopedRepositoryAccessPolicy(
            sessionFactory,
            (context, request) -> delegated.incrementAndGet(),
            audit::add,
            CLOCK);
    sessionFactory.close();

    assertThrows(
        RuntimeException.class,
        () ->
            policy.require(
                tokenAccess("token-1", "alice", 1, Set.of(GitRepositoryPermission.READ)),
                READ_REQUEST));
    assertEquals(0, delegated.get());
    assertEquals(1, audit.size());
    assertEquals(SecurityAuditOutcome.FAILED, audit.getFirst().outcome());
    assertEquals("AUTHORIZATION_EVALUATION_FAILED", audit.getFirst().reasonCode());
    provider.close();
  }

  private static void assertDenied(
      HibernateCredentialScopedRepositoryAccessPolicy policy,
      AuthenticatedGitAccess access,
      SecurityAuthenticationReason expectedReason) {
    RepositoryAccessDeniedException denied =
        assertThrows(
            RepositoryAccessDeniedException.class,
            () -> policy.require(access, READ_REQUEST));
    assertEquals(expectedReason.name(), denied.reasonCode());
  }

  private static AuthenticatedGitAccess tokenAccess(
      String tokenId,
      String principalId,
      long version,
      Set<GitRepositoryPermission> scopes) {
    GitAccessContext context =
        new GitAccessContext(
            principalId,
            Set.of(),
            "access_token",
            "session-" + tokenId,
            "correlation-" + tokenId,
            Map.of());
    return new AuthenticatedGitAccess(
        context,
        SecurityCredentialKind.ACCESS_TOKEN,
        tokenId,
        version,
        scopes);
  }

  private static SecurityAccessTokenEntity token(
      String tokenId,
      String principalId,
      String scopes,
      long securityVersion,
      Instant expiresAt) {
    SecurityAccessTokenEntity token = new SecurityAccessTokenEntity();
    token.setTokenId(tokenId);
    token.setPrincipalId(principalId);
    token.setTokenPrefix("jsh_" + tokenId.replace('-', 'x'));
    token.setTokenAlgorithm("TEST-HMAC");
    token.setTokenVersion(1);
    token.setTokenHash("hashed-" + tokenId);
    token.setPermissionScopes(scopes);
    token.setIssuedAt(START.minusSeconds(60));
    token.setExpiresAt(expiresAt);
    token.setLastUsedAt(null);
    token.setRevokedAt(null);
    token.setIssuedBy("admin");
    token.setSecurityVersion(securityVersion);
    return token;
  }

  private static void persistToken(
      SessionFactory sessionFactory, SecurityAccessTokenEntity token) {
    sessionFactory.inTransaction(session -> session.persist(token));
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:credential-revalidation-"
            + purpose
            + "-"
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
