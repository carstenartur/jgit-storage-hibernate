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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityLocalCredentialEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class HibernateSecurityCredentialServiceTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final Instant START = Instant.parse("2026-08-16T08:00:00Z");
  private static final String TOKEN_VALUE =
      "jsh_ABCDEFGHIJKLMNOP." + "S".repeat(43);
  private static final GitAccessContext ADMIN =
      new GitAccessContext(
          "admin", Set.of(), "oidc", "admin-session", "admin-correlation", Map.of());

  @Test
  void passwordLifecyclePersistsLockoutRehashAndAuditAtomically() {
    try (HibernateSessionFactoryProvider provider = provider("password")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice", SecurityPrincipalStatus.ACTIVE);
      MutableClock clock = new MutableClock(START);
      TestPasswordHasher passwordHasher = new TestPasswordHasher();
      HibernateSecurityIdentityAuditService audit = audit(sessionFactory, clock);
      HibernateSecurityCredentialService service =
          service(sessionFactory, clock, passwordHasher, audit, request -> {});

      LocalCredentialMetadata created =
          service.setPassword(passwordRequest(SecurityManagementOperation.SET_PASSWORD), chars("secret"));
      assertEquals(1, created.securityVersion());
      assertEquals(0, created.failedAttemptCount());
      assertTrue(service.findLocalCredential("alice").isPresent());

      SecurityAuthenticationException firstFailure =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.authenticatePassword(
                      "alice", chars("wrong"), trace("password-wrong-1")));
      assertEquals(SecurityAuthenticationReason.INVALID_CREDENTIALS, firstFailure.reason());
      assertNull(firstFailure.retryAt());

      SecurityAuthenticationException locked =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.authenticatePassword(
                      "alice", chars("wrong"), trace("password-wrong-2")));
      assertEquals(SecurityAuthenticationReason.PASSWORD_LOCKED, locked.reason());
      assertEquals(START.plus(Duration.ofMinutes(5)), locked.retryAt());
      LocalCredentialMetadata lockedMetadata = service.findLocalCredential("alice").orElseThrow();
      assertEquals(2, lockedMetadata.failedAttemptCount());
      assertTrue(lockedMetadata.lockedAt(START));

      SecurityAuthenticationException stillLocked =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.authenticatePassword(
                      "alice", chars("secret"), trace("password-locked")));
      assertEquals(SecurityAuthenticationReason.PASSWORD_LOCKED, stillLocked.reason());

      sessionFactory.inTransaction(
          session -> {
            SecurityLocalCredentialEntity credential =
                session.find(SecurityLocalCredentialEntity.class, "alice");
            credential.setPasswordHash("legacy:secret");
            credential.setSecurityVersion(10);
          });
      clock.advance(Duration.ofMinutes(5));
      AuthenticatedGitAccess access =
          service.authenticatePassword("alice", chars("secret"), trace("password-success"));
      assertEquals("alice", access.context().principalId());
      assertEquals("password", access.context().authenticationMethod());
      assertTrue(access.carries(GitRepositoryPermission.ADMINISTER));
      LocalCredentialMetadata authenticated = service.findLocalCredential("alice").orElseThrow();
      assertEquals(0, authenticated.failedAttemptCount());
      assertNull(authenticated.lockedUntil());
      assertTrue(authenticated.securityVersion() > 10);
      assertFalse(
          passwordHash(sessionFactory, "alice").startsWith("legacy:"),
          "successful authentication must replace a stale verifier");

      Optional<LocalCredentialMetadata> unlocked =
          service.unlockPassword(
              passwordRequest(SecurityManagementOperation.UNLOCK_PASSWORD));
      assertTrue(unlocked.isPresent());
      assertTrue(
          service.removePassword(
              passwordRequest(SecurityManagementOperation.REMOVE_PASSWORD)));
      assertFalse(
          service.removePassword(
              passwordRequest(SecurityManagementOperation.REMOVE_PASSWORD)));
      assertTrue(service.findLocalCredential("alice").isEmpty());
      assertTrue(
          service
              .unlockPassword(passwordRequest(SecurityManagementOperation.UNLOCK_PASSWORD))
              .isEmpty());

      SecurityAuthenticationException notConfigured =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.authenticatePassword(
                      "alice", chars("secret"), trace("password-missing")));
      assertEquals(
          SecurityAuthenticationReason.CREDENTIAL_NOT_CONFIGURED,
          notConfigured.reason());
      assertTrue(audit.findBySubjectPrincipal("alice", 100).size() >= 10);
    }
  }

  @Test
  void passwordAuthenticationConcealsUnknownInputAndRejectsInactivePrincipals() {
    try (HibernateSessionFactoryProvider provider = provider("password-negative")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "disabled", "disabled", SecurityPrincipalStatus.DISABLED);
      MutableClock clock = new MutableClock(START);
      HibernateSecurityCredentialService service =
          service(
              sessionFactory,
              clock,
              new TestPasswordHasher(),
              audit(sessionFactory, clock),
              request -> {});
      service.setPassword(
          SecurityManagementRequest.password(
              ADMIN, SecurityManagementOperation.SET_PASSWORD, "disabled"),
          chars("secret"));

      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticatePassword(
                          "unknown", chars("secret"), trace("unknown-login")))
              .reason());
      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () -> service.authenticatePassword(" ", new char[0], trace("bad-input")))
              .reason());
      assertEquals(
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticatePassword(
                          "disabled", chars("secret"), trace("disabled-login")))
              .reason());
      assertThrows(
          IllegalArgumentException.class,
          () -> service.findLocalCredential(" "));
    }
  }

  @Test
  void accessTokenLifecycleUsesOneWayStorageScopesExpiryAndRevocation() {
    try (HibernateSessionFactoryProvider provider = provider("token")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice", SecurityPrincipalStatus.ACTIVE);
      MutableClock clock = new MutableClock(START);
      HibernateSecurityIdentityAuditService audit = audit(sessionFactory, clock);
      HibernateSecurityCredentialService service =
          service(sessionFactory, clock, new TestPasswordHasher(), audit, request -> {});

      IssuedAccessToken issued =
          service.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              START.plus(Duration.ofHours(1)));
      assertEquals("token-1", issued.metadata().tokenId());
      assertEquals(TOKEN_VALUE, issued.tokenValue());
      assertFalse(issued.toString().contains(TOKEN_VALUE));
      assertEquals(1, service.findAccessTokens("alice", 10).size());
      assertEquals(issued.metadata(), service.findAccessToken("token-1").orElseThrow());

      sessionFactory.inTransaction(
          session -> {
            SecurityAccessTokenEntity token =
                session.find(SecurityAccessTokenEntity.class, "token-1");
            assertNotEquals(TOKEN_VALUE, token.getTokenHash());
            assertEquals("jsh_ABCDEFGHIJKLMNOP", token.getTokenPrefix());
          });

      AuthenticatedGitAccess access =
          service.authenticateAccessToken(TOKEN_VALUE, trace("token-success"));
      assertEquals(SecurityCredentialKind.ACCESS_TOKEN, access.credentialKind());
      assertTrue(access.carries(GitRepositoryPermission.READ));
      assertFalse(access.carries(GitRepositoryPermission.UPDATE_REF));
      AccessTokenMetadata used = service.findAccessToken("token-1").orElseThrow();
      assertEquals(START, used.lastUsedAt());
      assertTrue(used.securityVersion() > issued.metadata().securityVersion());

      AtomicInteger delegated = new AtomicInteger();
      CredentialScopedRepositoryAccessPolicy scopedPolicy =
          new CredentialScopedRepositoryAccessPolicy(
              (context, request) -> delegated.incrementAndGet(), SecurityAccessAuditRecorder.NONE);
      RepositoryName repository = new RepositoryName("demo");
      scopedPolicy.require(
          access,
          RepositoryAccessRequest.repository(repository, RepositoryAccessOperation.READ));
      assertEquals(1, delegated.get());
      RepositoryAccessDeniedException scopeDenied =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () ->
                  scopedPolicy.require(
                      access,
                      RepositoryAccessRequest.ref(
                          repository,
                          RepositoryAccessOperation.UPDATE_REF,
                          "refs/heads/main",
                          null,
                          null)));
      assertEquals("CREDENTIAL_SCOPE_DENY", scopeDenied.reasonCode());
      assertEquals(1, delegated.get());

      AuthenticatedGitAccess administrator =
          new AuthenticatedGitAccess(
              access.context(),
              SecurityCredentialKind.ACCESS_TOKEN,
              "token-admin",
              1,
              Set.of(GitRepositoryPermission.ADMINISTER));
      assertTrue(administrator.carries(GitRepositoryPermission.READ));
      assertTrue(administrator.carries(GitRepositoryPermission.DELETE_REF));

      AccessTokenMetadata revoked =
          service.revokeAccessToken(
              SecurityManagementRequest.revokeToken(ADMIN, "alice", "token-1"));
      AccessTokenMetadata revokedAgain =
          service.revokeAccessToken(
              SecurityManagementRequest.revokeToken(ADMIN, "alice", "token-1"));
      assertNotNull(revoked.revokedAt());
      assertEquals(revoked.revokedAt(), revokedAgain.revokedAt());
      assertEquals(revoked.securityVersion(), revokedAgain.securityVersion());
      assertEquals(
          SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticateAccessToken(
                          TOKEN_VALUE, trace("token-revoked")))
              .reason());
      assertTrue(audit.findByCredentialId("token-1", 100).size() >= 5);
    }
  }

  @Test
  void accessTokenAuthenticationRejectsMalformedUnknownExpiredAndInactiveTokens() {
    try (HibernateSessionFactoryProvider provider = provider("token-negative")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice", SecurityPrincipalStatus.ACTIVE);
      MutableClock clock = new MutableClock(START);
      AtomicInteger tokenSequence = new AtomicInteger();
      HibernateSecurityCredentialService service =
          new HibernateSecurityCredentialService(
              sessionFactory,
              new TestPasswordHasher(),
              new TestTokenHasher(),
              new PasswordLockoutPolicy(2, Duration.ofMinutes(5)),
              request -> {},
              audit(sessionFactory, clock),
              clock,
              () -> "token-" + tokenSequence.incrementAndGet(),
              () ->
                  tokenSequence.get() <= 1
                      ? TOKEN_VALUE
                      : "jsh_QRSTUVWXYZabcdef." + "T".repeat(43));

      assertEquals(
          SecurityAuthenticationReason.MALFORMED_ACCESS_TOKEN,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () -> service.authenticateAccessToken("bad", trace("malformed-token")))
              .reason());
      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticateAccessToken(
                          "jsh_ZYXWVUTSRQPONMLK." + "U".repeat(43),
                          trace("unknown-token")))
              .reason());
      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticateAccessToken(
                          "jsh_ABCDEFGHIJKLMNOP." + "X".repeat(43),
                          trace("wrong-token")))
              .reason());

      IssuedAccessToken expiring =
          service.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              START.plusSeconds(1));
      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticateAccessToken(
                          "jsh_ABCDEFGHIJKLMNOP." + "X".repeat(43),
                          trace("known-prefix-wrong-token")))
              .reason());
      clock.advance(Duration.ofSeconds(1));
      assertEquals(
          SecurityAuthenticationReason.ACCESS_TOKEN_EXPIRED,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticateAccessToken(
                          expiring.tokenValue(), trace("expired-token")))
              .reason());

      clock.advance(Duration.ofSeconds(1));
      IssuedAccessToken inactive =
          service.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              START.plus(Duration.ofHours(2)));
      setPrincipalStatus(sessionFactory, "alice", SecurityPrincipalStatus.LOCKED);
      assertEquals(
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticateAccessToken(
                          inactive.tokenValue(), trace("inactive-token")))
              .reason());

      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.issueAccessToken(
                  SecurityManagementRequest.issueToken(ADMIN, "alice"),
                  Set.of(),
                  null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.issueAccessToken(
                  SecurityManagementRequest.issueToken(ADMIN, "alice"),
                  Set.of(GitRepositoryPermission.READ),
                  START));
      assertThrows(IllegalArgumentException.class, () -> service.findAccessTokens("alice", 0));
      assertThrows(IllegalArgumentException.class, () -> service.findAccessToken(" "));
    }
  }

  @Test
  void credentialScopePolicyMapsEveryCoreOperationBeforeDelegating() {
    GitAccessContext context =
        new GitAccessContext(
            "alice", Set.of(), "access_token", "session", "correlation", Map.of());
    RepositoryName repository = new RepositoryName("mapping");
    AtomicInteger delegated = new AtomicInteger();
    CredentialScopedRepositoryAccessPolicy policy =
        new CredentialScopedRepositoryAccessPolicy(
            (effectiveContext, request) -> delegated.incrementAndGet());

    Map<RepositoryAccessOperation, GitRepositoryPermission> permissions =
        Map.of(
            RepositoryAccessOperation.DISCOVER, GitRepositoryPermission.DISCOVER,
            RepositoryAccessOperation.READ, GitRepositoryPermission.READ,
            RepositoryAccessOperation.CREATE_REF, GitRepositoryPermission.CREATE_REF,
            RepositoryAccessOperation.UPDATE_REF, GitRepositoryPermission.UPDATE_REF,
            RepositoryAccessOperation.DELETE_REF, GitRepositoryPermission.DELETE_REF,
            RepositoryAccessOperation.FORCE_UPDATE, GitRepositoryPermission.FORCE_UPDATE,
            RepositoryAccessOperation.DELETE_REPOSITORY, GitRepositoryPermission.ADMINISTER);
    permissions.forEach(
        (operation, permission) -> {
          AuthenticatedGitAccess access =
              new AuthenticatedGitAccess(
                  context,
                  SecurityCredentialKind.ACCESS_TOKEN,
                  "token-" + operation,
                  1,
                  Set.of(permission));
          RepositoryAccessRequest request =
              operation.refScoped()
                  ? RepositoryAccessRequest.ref(
                      repository, operation, "refs/heads/main", null, null)
                  : RepositoryAccessRequest.repository(repository, operation);
          policy.require(access, request);
        });
    assertEquals(RepositoryAccessOperation.values().length, delegated.get());
  }

  @Test
  void auditFailureRollsBackSuccessWhileAuthenticationDenialRemainsDenied() {
    try (HibernateSessionFactoryProvider provider = provider("audit-failure")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "bob", "bob", SecurityPrincipalStatus.ACTIVE);
      MutableClock clock = new MutableClock(START);
      TestPasswordHasher passwordHasher = new TestPasswordHasher();
      HibernateSecurityCredentialService working =
          service(
              sessionFactory,
              clock,
              passwordHasher,
              audit(sessionFactory, clock),
              request -> {});
      working.setPassword(
          SecurityManagementRequest.password(
              ADMIN, SecurityManagementOperation.SET_PASSWORD, "alice"),
          chars("secret"));

      SecurityIdentityAuditRecorder failingAudit =
          record -> {
            throw new SecurityIdentityAuditPersistenceException(
                "audit unavailable", new IllegalStateException("offline"));
          };
      HibernateSecurityCredentialService failing =
          service(sessionFactory, clock, passwordHasher, failingAudit, request -> {});

      assertThrows(
          SecurityIdentityAuditPersistenceException.class,
          () ->
              failing.setPassword(
                  SecurityManagementRequest.password(
                      ADMIN, SecurityManagementOperation.SET_PASSWORD, "bob"),
                  chars("bob-secret")));
      assertTrue(failing.findLocalCredential("bob").isEmpty());

      SecurityAuthenticationException denied =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  failing.authenticatePassword(
                      "alice", chars("wrong"), trace("denied-audit-failure")));
      assertEquals(SecurityAuthenticationReason.INVALID_CREDENTIALS, denied.reason());
      assertEquals(1, denied.getSuppressed().length);
      assertEquals(
          0,
          working.findLocalCredential("alice").orElseThrow().failedAttemptCount(),
          "failed-attempt state rolls back when its transactional audit cannot be appended");
    }
  }

  @Test
  void managementPolicyDenialAndFailuresRemainFailClosedAndAudited() {
    try (HibernateSessionFactoryProvider provider = provider("management-denial")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice", SecurityPrincipalStatus.ACTIVE);
      MutableClock clock = new MutableClock(START);
      HibernateSecurityIdentityAuditService audit = audit(sessionFactory, clock);
      SecurityManagementPolicy deniedPolicy =
          request -> {
            throw new SecurityManagementDeniedException(
                request, "NOT_ALLOWED", "policy-1", 7);
          };
      HibernateSecurityCredentialService deniedService =
          service(
              sessionFactory,
              clock,
              new TestPasswordHasher(),
              audit,
              deniedPolicy);

      SecurityManagementDeniedException denied =
          assertThrows(
              SecurityManagementDeniedException.class,
              () ->
                  deniedService.setPassword(
                      passwordRequest(SecurityManagementOperation.SET_PASSWORD),
                      chars("secret")));
      assertEquals("NOT_ALLOWED", denied.reasonCode());
      assertTrue(deniedService.findLocalCredential("alice").isEmpty());
      assertEquals(
          SecurityAuditOutcome.DENIED,
          audit.findBySubjectPrincipal("alice", 10).getFirst().record().outcome());

      HibernateSecurityCredentialService failingPolicyService =
          service(
              sessionFactory,
              clock,
              new TestPasswordHasher(),
              audit,
              request -> {
                throw new IllegalStateException("policy backend failed");
              });
      assertThrows(
          IllegalStateException.class,
          () ->
              failingPolicyService.removePassword(
                  passwordRequest(SecurityManagementOperation.REMOVE_PASSWORD)));
      assertEquals(
          SecurityAuditOutcome.FAILED,
          audit.findBySubjectPrincipal("alice", 10).getFirst().record().outcome());

      assertThrows(
          IllegalArgumentException.class,
          () ->
              deniedService.setPassword(
                  SecurityManagementRequest.issueToken(ADMIN, "alice"),
                  chars("secret")));
      assertThrows(
          io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException.class,
          () ->
              new HibernateSecurityCredentialService(
                      sessionFactory,
                      new TestPasswordHasher(),
                      new TestTokenHasher(),
                      PasswordLockoutPolicy.DEFAULT,
                      request -> {},
                      audit)
                  .revokeAccessToken(
                      SecurityManagementRequest.revokeToken(
                          ADMIN, "alice", "missing-token")));
    }
  }

  private static HibernateSecurityCredentialService service(
      SessionFactory sessionFactory,
      MutableClock clock,
      PasswordHasher passwordHasher,
      SecurityIdentityAuditRecorder auditRecorder,
      SecurityManagementPolicy policy) {
    return new HibernateSecurityCredentialService(
        sessionFactory,
        passwordHasher,
        new TestTokenHasher(),
        new PasswordLockoutPolicy(2, Duration.ofMinutes(5)),
        policy,
        auditRecorder,
        clock,
        () -> "token-1",
        () -> TOKEN_VALUE);
  }

  private static HibernateSecurityIdentityAuditService audit(
      SessionFactory sessionFactory, Clock clock) {
    AtomicInteger sequence = new AtomicInteger();
    return new HibernateSecurityIdentityAuditService(
        sessionFactory, clock, () -> "audit-" + sequence.incrementAndGet());
  }

  private static SecurityManagementRequest passwordRequest(
      SecurityManagementOperation operation) {
    return SecurityManagementRequest.password(ADMIN, operation, "alice");
  }

  private static SecurityAuthenticationTrace trace(String correlationId) {
    return SecurityAuthenticationTrace.withoutRemoteAddress(
        "session-" + correlationId, correlationId);
  }

  private static char[] chars(String value) {
    return value.toCharArray();
  }

  private static void persistPrincipal(
      SessionFactory sessionFactory,
      String principalId,
      String loginName,
      SecurityPrincipalStatus status) {
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId(principalId);
    principal.setPrincipalType(SecurityPrincipalType.USER);
    principal.setLoginName(loginName);
    principal.setDisplayName(principalId);
    principal.setStatus(status);
    principal.setCreatedAt(START);
    principal.setUpdatedAt(START);
    principal.setSecurityVersion(1);
    sessionFactory.inTransaction(session -> session.persist(principal));
  }

  private static void setPrincipalStatus(
      SessionFactory sessionFactory,
      String principalId,
      SecurityPrincipalStatus status) {
    sessionFactory.inTransaction(
        session -> {
          SecurityPrincipalEntity principal =
              session.find(SecurityPrincipalEntity.class, principalId);
          principal.setStatus(status);
          principal.setUpdatedAt(START.plusSeconds(1));
          principal.setSecurityVersion(principal.getSecurityVersion() + 1);
        });
  }

  private static String passwordHash(SessionFactory sessionFactory, String principalId) {
    return sessionFactory.fromTransaction(
        session ->
            session
                .find(SecurityLocalCredentialEntity.class, principalId)
                .getPasswordHash());
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:credential-service-"
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

  private static final class TestPasswordHasher implements PasswordHasher {

    @Override
    public PasswordHash hash(char[] password) {
      return new PasswordHash("TEST-PASSWORD", 1, "current:" + new String(password));
    }

    @Override
    public boolean verify(char[] password, PasswordHash expected) {
      String encoded = expected.encodedHash();
      int separator = encoded.indexOf(':');
      return separator >= 0
          && encoded.substring(separator + 1).equals(new String(password));
    }

    @Override
    public boolean needsRehash(PasswordHash existing) {
      return existing.encodedHash().startsWith("legacy:");
    }
  }

  private static final class TestTokenHasher implements AccessTokenHasher {

    @Override
    public AccessTokenHash hash(String tokenValue) {
      return new AccessTokenHash("TEST-TOKEN", 1, "hashed:" + tokenValue);
    }

    @Override
    public boolean verify(String tokenValue, AccessTokenHash expected) {
      return expected.encodedHash().equals("hashed:" + tokenValue);
    }
  }

  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    MutableClock(Instant instant) {
      this.instant = new AtomicReference<>(instant);
    }

    void advance(Duration duration) {
      instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("test clock supports UTC only");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
