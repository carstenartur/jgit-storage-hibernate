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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityCredentialContractsTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final GitAccessContext ACTOR =
      new GitAccessContext("admin", Set.of(), "oidc", "session-1", "correlation-1", Map.of());
  private static final SecurityAuthenticationTrace TRACE =
      SecurityAuthenticationTrace.withoutRemoteAddress("session-2", "correlation-2");

  @Test
  void hashAndLifecycleMetadataAreValidatedAndRedacted() {
    AccessTokenHash tokenHash = new AccessTokenHash("HMAC-SHA256", 1, "token-verifier");
    PasswordHash passwordHash = new PasswordHash("PBKDF2-HMAC-SHA256", 1, "password-verifier");
    PasswordLockoutPolicy lockout = new PasswordLockoutPolicy(3, Duration.ofMinutes(10));
    LocalCredentialMetadata credential =
        new LocalCredentialMetadata("alice", NOW, 2, NOW.plusSeconds(60), 4);
    AccessTokenMetadata active = metadata(null, NOW.plusSeconds(3600));
    AccessTokenMetadata revoked =
        new AccessTokenMetadata(
            "token-2",
            "alice",
            "jsh_prefix_2",
            Set.of(GitRepositoryPermission.READ),
            NOW,
            null,
            null,
            NOW.plusSeconds(1),
            "admin",
            6);

    assertFalse(tokenHash.toString().contains("token-verifier"));
    assertFalse(passwordHash.toString().contains("password-verifier"));
    assertEquals(3, lockout.maximumFailedAttempts());
    assertTrue(credential.lockedAt(NOW));
    assertFalse(credential.lockedAt(NOW.plusSeconds(60)));
    assertFalse(active.revoked());
    assertTrue(active.expiredAt(NOW.plusSeconds(3600)));
    assertTrue(revoked.revoked());

    assertThrows(IllegalArgumentException.class, () -> new AccessTokenHash(" ", 1, "hash"));
    assertThrows(IllegalArgumentException.class, () -> new AccessTokenHash("alg", 0, "hash"));
    assertThrows(IllegalArgumentException.class, () -> new AccessTokenHash("alg", 1, " "));
    assertThrows(IllegalArgumentException.class, () -> new PasswordHash(" ", 1, "hash"));
    assertThrows(IllegalArgumentException.class, () -> new PasswordHash("alg", 0, "hash"));
    assertThrows(IllegalArgumentException.class, () -> new PasswordHash("alg", 1, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PasswordLockoutPolicy(0, Duration.ofMinutes(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PasswordLockoutPolicy(1, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalCredentialMetadata(" ", NOW, 0, null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalCredentialMetadata("alice", NOW, -1, null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalCredentialMetadata("alice", NOW, 0, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccessTokenMetadata(
                "token", "alice", "prefix", Set.of(), NOW, null, null, null, "admin", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccessTokenMetadata(
                "token",
                "alice",
                "prefix",
                Set.of(GitRepositoryPermission.READ),
                NOW,
                NOW,
                null,
                null,
                "admin",
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccessTokenMetadata(
                "token",
                "alice",
                "prefix",
                Set.of(GitRepositoryPermission.READ),
                NOW,
                null,
                NOW.minusSeconds(1),
                null,
                "admin",
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccessTokenMetadata(
                "token",
                "alice",
                "prefix",
                Set.of(GitRepositoryPermission.READ),
                NOW,
                null,
                null,
                NOW.minusSeconds(1),
                "admin",
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AccessTokenMetadata(
                "token",
                "alice",
                "prefix",
                Set.of(GitRepositoryPermission.READ),
                NOW,
                null,
                null,
                null,
                "admin",
                0));
  }

  @Test
  void issuedAndAuthenticatedValuesKeepCredentialScopeExplicit() {
    AccessTokenMetadata metadata = metadata(null, null);
    IssuedAccessToken issued = new IssuedAccessToken(metadata, "jsh_prefix_secret");
    AuthenticatedGitAccess password =
        AuthenticatedGitAccess.unrestricted(
            context("password"), SecurityCredentialKind.PASSWORD, "alice", 3);
    AuthenticatedGitAccess token =
        new AuthenticatedGitAccess(
            context("access_token"),
            SecurityCredentialKind.ACCESS_TOKEN,
            "token-1",
            4,
            Set.of(GitRepositoryPermission.READ));

    assertSame(metadata, issued.metadata());
    assertFalse(issued.toString().contains(issued.tokenValue()));
    assertTrue(password.carries(GitRepositoryPermission.ADMINISTER));
    assertTrue(token.carries(GitRepositoryPermission.READ));
    assertFalse(token.carries(GitRepositoryPermission.UPDATE_REF));

    assertThrows(IllegalArgumentException.class, () -> new IssuedAccessToken(metadata, " "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthenticatedGitAccess(
                context("password"),
                SecurityCredentialKind.PASSWORD,
                " ",
                1,
                Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthenticatedGitAccess(
                context("password"),
                SecurityCredentialKind.PASSWORD,
                "id",
                -1,
                Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthenticatedGitAccess.unrestricted(
                context("access_token"), SecurityCredentialKind.ACCESS_TOKEN, "id", 1));
  }

  @Test
  void authenticationAndManagementEvidenceIsStableAndNonSecret() {
    SecurityAuthenticationTrace trace =
        new SecurityAuthenticationTrace("session", "correlation", "AB".repeat(32));
    SecurityAuthenticationException locked =
        new SecurityAuthenticationException(
            SecurityAuthenticationReason.PASSWORD_LOCKED, NOW.plusSeconds(60), null);
    SecurityManagementRequest revoke =
        SecurityManagementRequest.revokeToken(ACTOR, "alice", "token-1");
    SecurityManagementDeniedException denied =
        new SecurityManagementDeniedException(revoke, "NOT_OWNER", "policy-1", 7);

    assertEquals("ab".repeat(32), trace.remoteAddressHash());
    assertEquals(SecurityAuthenticationReason.PASSWORD_LOCKED, locked.reason());
    assertEquals(NOW.plusSeconds(60), locked.retryAt());
    assertSame(revoke, denied.request());
    assertEquals("NOT_OWNER", denied.reasonCode());
    assertEquals("policy-1", denied.evidenceId());
    assertEquals(7, denied.policyVersion());
    assertTrue(SecurityAuthenticationReason.PASSWORD_AUTHENTICATED.authenticated());
    assertFalse(SecurityAuthenticationReason.INVALID_CREDENTIALS.authenticated());

    assertThrows(
        IllegalArgumentException.class,
        () -> new SecurityAuthenticationTrace("session", "correlation", "bad"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityAuthenticationException(
                SecurityAuthenticationReason.PASSWORD_AUTHENTICATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityManagementRequest(
                ACTOR, SecurityManagementOperation.SET_PASSWORD, "alice", "unexpected"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityManagementRequest(
                ACTOR, SecurityManagementOperation.REVOKE_ACCESS_TOKEN, "alice", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SecurityManagementRequest.password(
                ACTOR, SecurityManagementOperation.ISSUE_ACCESS_TOKEN, "alice"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SecurityManagementDeniedException(revoke, " ", null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SecurityManagementDeniedException(revoke, "reason", null, -1));
  }

  @Test
  void identityAuditFactoriesCoverManagementAuthenticationAndFailureShapes() {
    for (SecurityManagementOperation operation : SecurityManagementOperation.values()) {
      SecurityManagementRequest request = request(operation);
      SecurityCredentialKind kind = credentialKind(operation);
      String credentialId =
          operation == SecurityManagementOperation.REVOKE_ACCESS_TOKEN ? "token-1" : null;
      SecurityIdentityAuditRecord record =
          SecurityIdentityAuditRecord.management(
              request, kind, credentialId, SecurityAuditOutcome.ALLOWED, "MANAGEMENT_ALLOWED");
      assertEquals(SecurityAuditOutcome.ALLOWED, record.outcome());
      assertEquals("admin", record.actorPrincipalId());
    }

    SecurityManagementRequest passwordRequest =
        SecurityManagementRequest.password(
            ACTOR, SecurityManagementOperation.SET_PASSWORD, "alice");
    SecurityIdentityAuditRecord failedManagement =
        SecurityIdentityAuditRecord.failedManagement(
            passwordRequest,
            SecurityCredentialKind.PASSWORD,
            null,
            "MANAGEMENT_FAILED",
            new IllegalStateException("failure"));
    SecurityIdentityAuditRecord passwordAllowed =
        SecurityIdentityAuditRecord.authentication(
            SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
            "alice",
            TRACE,
            SecurityCredentialKind.PASSWORD,
            "alice",
            SecurityAuditOutcome.ALLOWED,
            SecurityAuthenticationReason.PASSWORD_AUTHENTICATED);
    SecurityIdentityAuditRecord tokenDenied =
        SecurityIdentityAuditRecord.authentication(
            SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
            null,
            TRACE,
            SecurityCredentialKind.ACCESS_TOKEN,
            null,
            SecurityAuditOutcome.DENIED,
            SecurityAuthenticationReason.INVALID_CREDENTIALS);
    SecurityIdentityAuditRecord failedAuthentication =
        SecurityIdentityAuditRecord.failedAuthentication(
            SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
            "alice",
            TRACE,
            SecurityCredentialKind.ACCESS_TOKEN,
            "token-1",
            new IllegalStateException("failure"));
    SecurityIdentityAuditEvent event =
        new SecurityIdentityAuditEvent("audit-1", NOW, passwordAllowed);

    assertEquals(SecurityAuditOutcome.FAILED, failedManagement.outcome());
    assertEquals("password", passwordAllowed.authenticationMethod());
    assertEquals("access_token", tokenDenied.authenticationMethod());
    assertEquals(SecurityAuditOutcome.FAILED, failedAuthentication.outcome());
    assertEquals("audit-1", event.auditId());
    assertSame(passwordAllowed, event.record());
    SecurityIdentityAuditRecorder.NONE.record(passwordAllowed);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SecurityIdentityAuditRecord.management(
                passwordRequest,
                SecurityCredentialKind.PASSWORD,
                null,
                SecurityAuditOutcome.FAILED,
                "failed"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SecurityIdentityAuditRecord.authentication(
                SecurityIdentityAuditOperation.PASSWORD_SET,
                "alice",
                TRACE,
                SecurityCredentialKind.PASSWORD,
                "alice",
                SecurityAuditOutcome.ALLOWED,
                SecurityAuthenticationReason.PASSWORD_AUTHENTICATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SecurityIdentityAuditRecord.authentication(
                SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
                "alice",
                TRACE,
                SecurityCredentialKind.PASSWORD,
                "alice",
                SecurityAuditOutcome.DENIED,
                SecurityAuthenticationReason.PASSWORD_AUTHENTICATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityIdentityAuditRecord(
                SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
                SecurityAuditOutcome.ALLOWED,
                null,
                "alice",
                "password",
                "session",
                "correlation",
                "bad",
                SecurityCredentialKind.PASSWORD,
                "alice",
                "ok",
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityIdentityAuditRecord(
                SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
                SecurityAuditOutcome.ALLOWED,
                null,
                "alice",
                "password",
                "session",
                "correlation",
                null,
                SecurityCredentialKind.PASSWORD,
                "alice",
                "ok",
                "failure.Type"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SecurityIdentityAuditEvent(" ", NOW, passwordAllowed));

    SecurityIdentityAuditPersistenceException persistence =
        new SecurityIdentityAuditPersistenceException("audit failed", new IllegalStateException());
    assertEquals("audit failed", persistence.getMessage());
  }

  private static SecurityManagementRequest request(SecurityManagementOperation operation) {
    if (operation == SecurityManagementOperation.REVOKE_ACCESS_TOKEN) {
      return SecurityManagementRequest.revokeToken(ACTOR, "alice", "token-1");
    }
    if (operation == SecurityManagementOperation.ISSUE_ACCESS_TOKEN) {
      return SecurityManagementRequest.issueToken(ACTOR, "alice");
    }
    return SecurityManagementRequest.password(ACTOR, operation, "alice");
  }

  private static SecurityCredentialKind credentialKind(SecurityManagementOperation operation) {
    return operation == SecurityManagementOperation.ISSUE_ACCESS_TOKEN
            || operation == SecurityManagementOperation.REVOKE_ACCESS_TOKEN
        ? SecurityCredentialKind.ACCESS_TOKEN
        : SecurityCredentialKind.PASSWORD;
  }

  private static GitAccessContext context(String method) {
    return new GitAccessContext("alice", Set.of(), method, "session", "correlation", Map.of());
  }

  private static AccessTokenMetadata metadata(Instant revokedAt, Instant expiresAt) {
    return new AccessTokenMetadata(
        "token-1",
        "alice",
        "jsh_prefix",
        Set.of(GitRepositoryPermission.READ),
        NOW,
        expiresAt,
        null,
        revokedAt,
        "admin",
        5);
  }
}
