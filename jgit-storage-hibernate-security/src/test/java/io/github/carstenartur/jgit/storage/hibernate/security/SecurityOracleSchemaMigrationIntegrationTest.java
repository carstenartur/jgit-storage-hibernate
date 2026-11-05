/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.security.SecuritySchemaMigrationIntegrationTest.TestDatabase;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityLocalCredentialEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityManagedPolicyEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityVersionEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.schema.SecuritySchemaMigrations;
import jakarta.persistence.OptimisticLockException;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/** Oracle 23+ fresh-install contracts; Docker-skipped runs are not qualification evidence. */
@Testcontainers(disabledWithoutDocker = true)
class SecurityOracleSchemaMigrationIntegrationTest {
  @Container
  static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23.26.3-slim-faststart")
          .withStartupTimeout(Duration.ofMinutes(5));

  private static final Instant TIME =
      OffsetDateTime.parse("2026-08-16T12:34:56.123456789+05:45").toInstant();
  private static final String MANAGED_REPO = "庫".repeat(255);
  private static final String SOURCE = "源".repeat(128);
  private static final String INSTANCE = "設".repeat(128);
  private static final Clock CLOCK = Clock.fixed(TIME, ZoneId.of("Asia/Kathmandu"));
  private static final GitAccessContext ADMIN = context("admin");
  private static final AtomicInteger AUDIT_IDS = new AtomicInteger();
  private static final String TOKEN = "jsh_ABCDEFGHIJKLMNOP." + "S".repeat(43);

  private static TestDatabase database() {
    return new TestDatabase(
        ORACLE.getJdbcUrl(),
        ORACLE.getUsername(),
        ORACLE.getPassword(),
        "oracle.jdbc.OracleDriver",
        "org.hibernate.dialect.OracleDialect",
        CoreSchemaMigrations.ORACLE_LOCATION,
        SecuritySchemaMigrations.ORACLE_LOCATION,
        () -> {});
  }

  @BeforeAll
  static void migrate() {
    flyway(CoreSchemaMigrations.ORACLE_LOCATION, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .migrate();
    flyway(SecuritySchemaMigrations.ORACLE_LOCATION, SecuritySchemaMigrations.SCHEMA_HISTORY_TABLE)
        .migrate();
  }

  @Test
  void validatesAllElevenEntitiesAndMigrationHistoryAcrossRestart() throws Exception {
    SecuritySchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database(), "0.12.0");
    for (Flyway migration :
        List.of(
            flyway(CoreSchemaMigrations.ORACLE_LOCATION, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE),
            flyway(
                SecuritySchemaMigrations.ORACLE_LOCATION,
                SecuritySchemaMigrations.SCHEMA_HISTORY_TABLE))) {
      migration.validate();
      assertEquals("0.12.0", migration.info().current().getVersion().getVersion());
      assertEquals(0, migration.migrate().migrationsExecuted);
    }
    try (var provider = provider()) {
      SessionFactory factory = provider.getSessionFactory();
      factory.inTransaction(
          session -> {
            SecurityManagedPolicyEntity policy = new SecurityManagedPolicyEntity();
            policy.setPolicyId("managed-policy");
            policy.setRepositoryName("workflows");
            policy.setManagedSourceId("source");
            policy.setManagedSourceInstanceId("instance");
            policy.setOwnershipMode(RepositoryPolicyOwnershipMode.NAMESPACE_ONLY);
            policy.setPolicyVersion(3);
            policy.setContentDigest("a".repeat(64));
            policy.setPolicyGeneration(2);
            policy.setCreatedAt(TIME);
            policy.setUpdatedAt(TIME);
            policy.setCreatedByPrincipalId("admin");
            policy.setUpdatedByPrincipalId("admin");
            policy.setLastOperationId("synchronize");
            policy.setLastCorrelationId("managed-correlation");
            session.persist(policy);
          });
    }
    try (var provider = provider()) {
      provider
          .getSessionFactory()
          .inTransaction(
              session -> {
                var policy = session.find(SecurityManagedPolicyEntity.class, "managed-policy");
                assertEquals(TIME, policy.getUpdatedAt());
                assertEquals(
                    RepositoryPolicyOwnershipMode.NAMESPACE_ONLY, policy.getOwnershipMode());
                assertEquals(3, policy.getPolicyVersion());
                assertEquals(2, policy.getPolicyGeneration());
                assertEquals("a".repeat(64), policy.getContentDigest());
              });
    }
    try (var connection =
        DriverManager.getConnection(
            ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword())) {
      assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 23);
      try (var statement = connection.createStatement()) {
        Set<String> indexes = new HashSet<>();
        try (var rows =
            statement.executeQuery(
                "select index_name from user_indexes where table_name like 'GIT_SECURITY_%'")) {
          while (rows.next()) {
            indexes.add(rows.getString(1));
          }
        }
        assertTrue(
            indexes.containsAll(
                Set.of(
                    "UK_GIT_SEC_MANAGED_GRANT_ENTRY",
                    "UK_GIT_SEC_MANAGED_REF_RULE_ENTRY",
                    "UK_GIT_SEC_PRINCIPAL_EXTERNAL",
                    "UK_GIT_SEC_ACCESS_TOKEN_PREFIX")),
            indexes.toString());
        try (var rows =
            statement.executeQuery(
                "select data_type from user_tab_columns where table_name = 'GIT_SECURITY_REF_RULE'"
                    + " and column_name = 'ENABLED'")) {
          assertTrue(rows.next());
          assertEquals("BOOLEAN", rows.getString(1));
        }
      }
    }
    // Credentials, tokens and identity audit are exercised through their public services below.
    assertEquals(11, SecurityEntities.annotatedClasses().size());
  }

  @Test
  void preservesExternalAndLocalNullUniquenessAndUnicodeBoundaryKeys() throws Exception {
    ExternalPrincipalBindingDatabaseContract.verify(database());
    String issuerPrefix = "https://issuer.test/";
    String issuer = issuerPrefix + "界".repeat(512 - issuerPrefix.length());
    String subject = "語".repeat(512);
    try (var provider = provider()) {
      var factory = provider.getSessionFactory();
      factory.inTransaction(
          session -> {
            var local = principal("local-null-1", null);
            local.setDisplayName("");
            session.persist(local);
            session.persist(principal("local-null-2", null));
            var external = principal("unicode-external", null);
            external.setPrincipalType(SecurityPrincipalType.EXTERNAL);
            external.setExternalIssuer(issuer);
            external.setExternalSubject(subject);
            session.persist(external);
          });
      constraintRejected(
          1,
          () ->
              factory.inTransaction(
                  session -> {
                    var duplicate = principal("duplicate-external", null);
                    duplicate.setExternalIssuer(issuer);
                    duplicate.setExternalSubject(subject);
                    session.persist(duplicate);
                  }));
      constraintRejected(
          2290,
          () ->
              factory.inTransaction(
                  session -> {
                    var half = principal("half-external", null);
                    half.setExternalIssuer("issuer-only");
                    session.persist(half);
                  }));
    }
    try (var provider = provider()) {
      assertEquals(
          "unicode-external",
          new HibernateExternalPrincipalBindingService(provider.getSessionFactory())
              .resolve(
                  ExternalPrincipalIdentity.of(issuer, subject, null),
                  ExternalPrincipalProvisioningPolicy.EXISTING_ONLY)
              .principalId());
      provider
          .getSessionFactory()
          .inTransaction(
              session -> {
                var principal = session.find(SecurityPrincipalEntity.class, "unicode-external");
                assertEquals(issuer, principal.getExternalIssuer());
                assertEquals(subject, principal.getExternalSubject());
                assertNull(principal.getLoginName());
                assertNull(
                    session.find(SecurityPrincipalEntity.class, "local-null-1").getDisplayName());
                assertEquals(TIME, principal.getCreatedAt());
              });
    }
  }

  @Test
  void allowsMultipleUnmanagedRowsButEnforcesOwnedKeysAndCompleteTuples() throws Exception {
    try (var provider = provider()) {
      var factory = provider.getSessionFactory();
      factory.inTransaction(
          session -> {
            session.persist(grant("unmanaged-1", MANAGED_REPO, "subject-1"));
            session.persist(grant("unmanaged-2", MANAGED_REPO, "subject-2"));
            session.persist(rule("unmanaged-rule-1", MANAGED_REPO, true));
            session.persist(rule("unmanaged-rule-2", MANAGED_REPO, false));
            for (int n = 1; n <= 3; n++) {
              var grant = grant("owned-" + n, MANAGED_REPO, "owned-subject-" + n);
              grant.setManagedSourceId(n == 3 ? "other-source" : SOURCE);
              grant.setManagedSourceInstanceId(INSTANCE);
              grant.setManagedEntryKey(n == 2 ? "different-key" : "鍵".repeat(256));
              grant.setManagedPolicyVersion(1L);
              session.persist(grant);
              var rule = rule("owned-rule-" + n, MANAGED_REPO, true);
              rule.setManagedSourceId(grant.getManagedSourceId());
              rule.setManagedSourceInstanceId(INSTANCE);
              rule.setManagedEntryKey(grant.getManagedEntryKey());
              rule.setManagedPolicyVersion(1L);
              session.persist(rule);
            }
          });
      for (String table : List.of("git_security_repository_grant", "git_security_ref_rule")) {
        String id = table.endsWith("grant") ? "grant_id" : "rule_id";
        String row = table.endsWith("grant") ? "owned-2" : "owned-rule-2";
        // Updates avoid accidental rejection by a different natural/primary key.
        sqlRejected(
            "update "
                + table
                + " set managed_entry_key = '"
                + "鍵".repeat(256)
                + "' where "
                + id
                + " = '"
                + row
                + "'",
            1);
        for (int version : List.of(0, -1)) {
          sqlRejected(
              "update "
                  + table
                  + " set managed_policy_version = "
                  + version
                  + " where "
                  + id
                  + " = '"
                  + row
                  + "'");
        }
        for (int mask = 1; mask < 15; mask++) {
          String[] columns = {
            "managed_source_id",
            "managed_source_instance_id",
            "managed_entry_key",
            "managed_policy_version"
          };
          List<String> assignments = new ArrayList<>();
          for (int bit = 0; bit < 4; bit++) {
            assignments.add(
                columns[bit]
                    + " = "
                    + ((mask & (1 << bit)) == 0 ? "null" : bit == 3 ? "1" : "'partial'"));
          }
          sqlRejected(
              "update "
                  + table
                  + " set "
                  + String.join(", ", assignments)
                  + " where "
                  + id
                  + " = '"
                  + row
                  + "'");
        }
      }
    }
    try (var provider = provider()) {
      provider
          .getSessionFactory()
          .inTransaction(
              session -> {
                assertNull(
                    session
                        .find(SecurityRepositoryGrantEntity.class, "unmanaged-1")
                        .getManagedSourceId());
                assertFalse(
                    session.find(SecurityRefRuleEntity.class, "unmanaged-rule-2").isEnabled());
                assertEquals(
                    "鍵".repeat(256),
                    session.find(SecurityRefRuleEntity.class, "owned-rule-1").getManagedEntryKey());
              });
    }
  }

  @Test
  void evaluatesGroupAllowAndEnabledRefDenyAndReloadsRevokedPolicyWithAudit() throws Exception {
    try (var provider = provider()) {
      provider
          .getSessionFactory()
          .inTransaction(
              session -> {
                session.persist(principal("policy-user", "policy-user"));
                var group = new SecurityGroupEntity();
                group.setGroupId("policy-group");
                group.setGroupName("貢献者");
                group.setStatus(SecurityGroupStatus.ACTIVE);
                group.setCreatedAt(TIME);
                group.setUpdatedAt(TIME);
                group.setSecurityVersion(1);
                session.persist(group);
                var membership = new SecurityGroupMembershipEntity();
                membership.setMembershipId("policy-membership");
                membership.setPrincipalId("policy-user");
                membership.setGroupId("policy-group");
                membership.setCreatedAt(TIME);
                membership.setCreatedBy("admin");
                membership.setSecurityVersion(2);
                session.persist(membership);
                var grant = grant("policy-grant", "policy-repo", "policy-group");
                grant.setSubjectType(SecuritySubjectType.GROUP);
                session.persist(grant);
                var enabled = rule("policy-deny", "policy-repo", true);
                enabled.setRefPattern("refs/heads/protected/**");
                session.persist(enabled);
                var disabled = rule("policy-disabled", "policy-repo", false);
                disabled.setRefPattern("refs/heads/open/**");
                session.persist(disabled);
              });
    }
    var context = context("policy-user");
    var open = request("policy-repo", "refs/heads/open/main");
    var protectedRef = request("policy-repo", "refs/heads/protected/main");
    try (var provider = provider()) {
      var factory = provider.getSessionFactory();
      var audit =
          new HibernateSecurityAccessAuditService(
              factory, CLOCK, () -> "access-" + AUDIT_IDS.incrementAndGet());
      var policy = new HibernateSecurityRepositoryAccessPolicy(factory, audit);
      policy.require(context, open);
      assertThrows(
          RepositoryAccessDeniedException.class, () -> policy.require(context, protectedRef));
      factory.inTransaction(
          session ->
              session.remove(
                  session.find(SecurityGroupMembershipEntity.class, "policy-membership")));
      assertThrows(RepositoryAccessDeniedException.class, () -> policy.require(context, open));
    }
    try (var provider = provider()) {
      var later =
          new HibernateSecurityAccessAuditService(
              provider.getSessionFactory(),
              Clock.fixed(TIME.plusNanos(1), CLOCK.getZone()),
              () -> "access-later");
      later.record(
          SecurityAccessAuditRecord.failed(
              context, open, new IllegalStateException("test failure")));
    }
    try (var provider = provider()) {
      var events =
          new HibernateSecurityAccessAuditService(provider.getSessionFactory())
              .findByRepository(new RepositoryName("policy-repo"), 10);
      assertEquals(4, events.size());
      assertEquals(TIME.plusNanos(1), events.getFirst().occurredAt());
      assertEquals(IllegalStateException.class.getName(), events.getFirst().record().failureType());
      assertEquals(
          2,
          events.stream().filter(e -> e.record().outcome() == SecurityAuditOutcome.DENIED).count());
      assertTrue(events.subList(1, 4).stream().allMatch(e -> e.occurredAt().equals(TIME)));
      assertTrue(events.subList(1, 4).stream().allMatch(e -> e.record().failureType() == null));
      sqlRejected(
          "update git_security_access_audit set failure_type = null where audit_id ="
              + " 'access-later'");
    }
  }

  @Test
  void persistsPasswordLockoutTokenRevocationAndOneWayVerifiersWithIdentityAudit() {
    String tokenId;
    try (var provider = provider()) {
      var factory = provider.getSessionFactory();
      factory.inTransaction(
          session -> session.persist(principal("credential-user", "credential-login")));
      var service = credentials(factory);
      service.setPassword(
          SecurityManagementRequest.password(
              ADMIN, SecurityManagementOperation.SET_PASSWORD, "credential-user"),
          "秘密-password".toCharArray());
      assertEquals(
          "credential-user",
          service
              .authenticatePassword(
                  "credential-login", "秘密-password".toCharArray(), trace("password-ok"))
              .context()
              .principalId());
      for (int i = 0; i < 2; i++) {
        assertThrows(
            SecurityAuthenticationException.class,
            () ->
                service.authenticatePassword(
                    "credential-login", "bad".toCharArray(), trace("password-bad")));
      }
      assertEquals(
          2, service.findLocalCredential("credential-user").orElseThrow().failedAttemptCount());
      var issued =
          service.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "credential-user"),
              Set.of(GitRepositoryPermission.READ),
              TIME.plusSeconds(3600));
      tokenId = issued.metadata().tokenId();
      assertEquals(
          "credential-user",
          service
              .authenticateAccessToken(issued.tokenValue(), trace("token-ok"))
              .context()
              .principalId());
      service.revokeAccessToken(
          SecurityManagementRequest.revokeToken(ADMIN, "credential-user", tokenId));
    }
    try (var provider = provider()) {
      var factory = provider.getSessionFactory();
      var service = credentials(factory);
      var locked =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  service.authenticatePassword(
                      "credential-login", "秘密-password".toCharArray(), trace("locked-restart")));
      // Password denials conceal lockout details; precise reasons are durable audit evidence.
      assertEquals(SecurityAuthenticationReason.INVALID_CREDENTIALS, locked.reason());
      assertNull(locked.retryAt());
      var lockedMetadata = service.findLocalCredential("credential-user").orElseThrow();
      assertEquals(2, lockedMetadata.failedAttemptCount());
      assertEquals(TIME.plusSeconds(300), lockedMetadata.lockedUntil());
      assertTrue(lockedMetadata.lockedAt(TIME));
      assertEquals(
          SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () -> service.authenticateAccessToken(TOKEN, trace("revoked-restart")))
              .reason());
      factory.inTransaction(
          session -> {
            var credential = session.find(SecurityLocalCredentialEntity.class, "credential-user");
            assertEquals(Pbkdf2PasswordHasher.ALGORITHM, credential.getPasswordAlgorithm());
            assertEquals(Pbkdf2PasswordHasher.VERSION, credential.getPasswordVersion());
            assertFalse(credential.getPasswordHash().contains("password"));
            assertEquals(TIME, credential.getChangedAt());
            assertEquals(TIME.plusSeconds(300), credential.getLockedUntil());
            var token = session.find(SecurityAccessTokenEntity.class, tokenId);
            assertEquals(HmacSha256AccessTokenHasher.ALGORITHM, token.getTokenAlgorithm());
            assertFalse(token.getTokenHash().contains(TOKEN));
            assertEquals("READ", token.getPermissionScopes());
            assertEquals(TIME, token.getRevokedAt());
            assertEquals(TIME, token.getLastUsedAt());
          });
      var audit = new HibernateSecurityIdentityAuditService(factory);
      var lockoutEvents = audit.findByCorrelationId("locked-restart", 10);
      assertEquals(1, lockoutEvents.size());
      var lockoutEvent = lockoutEvents.getFirst();
      assertEquals(TIME, lockoutEvent.occurredAt());
      assertEquals(
          SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
          lockoutEvent.record().operation());
      assertEquals(SecurityAuditOutcome.DENIED, lockoutEvent.record().outcome());
      assertEquals(
          SecurityAuthenticationReason.PASSWORD_LOCKED.name(), lockoutEvent.record().reasonCode());
      assertEquals("credential-user", lockoutEvent.record().subjectPrincipalId());
      assertEquals(SecurityCredentialKind.PASSWORD, lockoutEvent.record().credentialKind());
      assertTrue(audit.findBySubjectPrincipal("credential-user", 100).size() >= 8);
      assertTrue(audit.findByCredentialId(tokenId, 100).size() >= 4);
      assertEquals(TIME, audit.findByCorrelationId("revoked-restart", 10).getFirst().occurredAt());
    }
  }

  @Test
  void rejectsStaleOptimisticVersionAndSerializesPessimisticVersionUpdates() throws Exception {
    try (var provider = provider()) {
      var factory = provider.getSessionFactory();
      factory.inTransaction(
          session -> {
            var version = new SecurityVersionEntity();
            version.setScopeKey("oracle-concurrent");
            version.setVersionValue(1);
            session.persist(version);
          });
      try (var first = factory.openSession();
          var stale = factory.openSession()) {
        var one = first.beginTransaction();
        var two = stale.beginTransaction();
        var current = first.find(SecurityVersionEntity.class, "oracle-concurrent");
        var old = stale.find(SecurityVersionEntity.class, "oracle-concurrent");
        current.setVersionValue(2);
        one.commit();
        old.setVersionValue(3);
        assertThrows(OptimisticLockException.class, two::commit);
      }
      ExecutorService executor = Executors.newSingleThreadExecutor();
      CountDownLatch attemptingLock = new CountDownLatch(1);
      try (var first = factory.openSession()) {
        var transaction = first.beginTransaction();
        var value =
            first.find(
                SecurityVersionEntity.class,
                "oracle-concurrent",
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Future<?> competing =
            executor.submit(
                () ->
                    factory.inTransaction(
                        session -> {
                          attemptingLock.countDown();
                          var locked =
                              session.find(
                                  SecurityVersionEntity.class,
                                  "oracle-concurrent",
                                  jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                          locked.setVersionValue(locked.getVersionValue() + 1);
                        }));
        assertTrue(attemptingLock.await(30, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> competing.get(200, TimeUnit.MILLISECONDS));
        value.setVersionValue(value.getVersionValue() + 1);
        transaction.commit();
        competing.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
    try (var provider = provider()) {
      provider
          .getSessionFactory()
          .inTransaction(
              session ->
                  assertEquals(
                      4,
                      session
                          .find(SecurityVersionEntity.class, "oracle-concurrent")
                          .getVersionValue()));
    }
  }

  private static HibernateSecurityCredentialService credentials(SessionFactory factory) {
    return new HibernateSecurityCredentialService(
        factory,
        new Pbkdf2PasswordHasher(new SecureRandom(), 100_000, 16, 32),
        new HmacSha256AccessTokenHasher(
            "oracle-test-pepper-32-bytes-long!!"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
        new PasswordLockoutPolicy(2, Duration.ofMinutes(5)),
        request -> {},
        new HibernateSecurityIdentityAuditService(
            factory, CLOCK, () -> "identity-" + AUDIT_IDS.incrementAndGet()),
        CLOCK,
        () -> "oracle-token",
        () -> TOKEN);
  }

  private static void constraintRejected(int errorCode, Runnable action) {
    Throwable rejected = assertThrows(RuntimeException.class, action::run);
    while (!(rejected instanceof SQLException) && rejected.getCause() != null) {
      rejected = rejected.getCause();
    }
    assertEquals(errorCode, assertInstanceOf(SQLException.class, rejected).getErrorCode());
  }

  private static void sqlRejected(String sql) throws SQLException {
    sqlRejected(sql, 2290);
  }

  private static void sqlRejected(String sql, int expectedErrorCode) throws SQLException {
    try (var connection =
        DriverManager.getConnection(
            ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword())) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        SQLException rejected =
            assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
        assertEquals(expectedErrorCode, rejected.getErrorCode(), rejected.toString());
      } finally {
        connection.rollback();
      }
    }
  }

  private static SecurityPrincipalEntity principal(String id, String login) {
    var value = new SecurityPrincipalEntity();
    value.setPrincipalId(id);
    value.setLoginName(login);
    value.setPrincipalType(SecurityPrincipalType.USER);
    value.setStatus(SecurityPrincipalStatus.ACTIVE);
    value.setCreatedAt(TIME);
    value.setUpdatedAt(TIME);
    value.setSecurityVersion(1);
    return value;
  }

  private static SecurityRepositoryGrantEntity grant(String id, String repo, String subject) {
    var value = new SecurityRepositoryGrantEntity();
    value.setGrantId(id);
    value.setRepositoryName(repo);
    value.setSubjectType(SecuritySubjectType.PRINCIPAL);
    value.setSubjectId(subject);
    value.setPermission(GitRepositoryPermission.UPDATE_REF);
    value.setEffect(SecurityEffect.ALLOW);
    value.setCreatedAt(TIME);
    value.setCreatedBy("admin");
    value.setSecurityVersion(1);
    return value;
  }

  private static SecurityRefRuleEntity rule(String id, String repo, boolean enabled) {
    var value = new SecurityRefRuleEntity();
    value.setRuleId(id);
    value.setRepositoryName(repo);
    value.setRefPattern("refs/heads/**");
    value.setPermission(GitRepositoryPermission.UPDATE_REF);
    value.setEffect(SecurityEffect.DENY);
    value.setPriority(100);
    value.setEnabled(enabled);
    value.setCreatedAt(TIME);
    value.setCreatedBy("admin");
    value.setSecurityVersion(1);
    return value;
  }

  private static GitAccessContext context(String principal) {
    return new GitAccessContext(
        principal, Set.of(), "password", "oracle-session", "oracle-correlation", Map.of());
  }

  private static SecurityAuthenticationTrace trace(String correlation) {
    return SecurityAuthenticationTrace.withoutRemoteAddress("oracle-session", correlation);
  }

  private static RepositoryAccessRequest request(String repo, String ref) {
    return RepositoryAccessRequest.ref(
        new RepositoryName(repo), RepositoryAccessOperation.UPDATE_REF, ref, null, null);
  }

  private static Flyway flyway(String location, String table) {
    return Flyway.configure()
        .dataSource(ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword())
        .locations(location)
        .table(table)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load();
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = database().hibernateProperties();
    properties.setProperty("hibernate.hbm2ddl.auto", "validate");
    properties.setProperty("hibernate.show_sql", "false");
    properties.setProperty(
        "hibernate.connection.init_sql", "alter session set time_zone = '+05:45'");
    return new HibernateSessionFactoryProvider(properties, SecurityEntities.annotatedClasses());
  }
}
