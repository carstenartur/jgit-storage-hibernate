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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityIdentityAuditEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityLocalCredentialEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SecurityCredentialEntitiesTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void localCredentialEntityExposesEveryMappedValue() {
    SecurityLocalCredentialEntity entity = new SecurityLocalCredentialEntity();
    entity.setPrincipalId("alice");
    entity.setPasswordAlgorithm("PBKDF2-HMAC-SHA256");
    entity.setPasswordVersion(1);
    entity.setPasswordHash("encoded");
    entity.setChangedAt(NOW);
    entity.setFailedAttemptCount(3);
    entity.setLockedUntil(NOW.plusSeconds(60));
    entity.setSecurityVersion(7);

    assertEquals("alice", entity.getPrincipalId());
    assertEquals("PBKDF2-HMAC-SHA256", entity.getPasswordAlgorithm());
    assertEquals(1, entity.getPasswordVersion());
    assertEquals("encoded", entity.getPasswordHash());
    assertEquals(NOW, entity.getChangedAt());
    assertEquals(3, entity.getFailedAttemptCount());
    assertEquals(NOW.plusSeconds(60), entity.getLockedUntil());
    assertEquals(0, entity.getEntityVersion());
    assertEquals(7, entity.getSecurityVersion());
  }

  @Test
  void accessTokenEntityExposesEveryMappedValue() {
    SecurityAccessTokenEntity entity = new SecurityAccessTokenEntity();
    entity.setTokenId("token-1");
    entity.setPrincipalId("alice");
    entity.setTokenPrefix("jsh_prefix");
    entity.setTokenAlgorithm("HMAC-SHA256");
    entity.setTokenVersion(1);
    entity.setTokenHash("encoded");
    entity.setPermissionScopes("READ,UPDATE_REF");
    entity.setIssuedAt(NOW);
    entity.setExpiresAt(NOW.plusSeconds(3600));
    entity.setLastUsedAt(NOW.plusSeconds(30));
    entity.setRevokedAt(NOW.plusSeconds(60));
    entity.setIssuedBy("admin");
    entity.setSecurityVersion(8);

    assertEquals("token-1", entity.getTokenId());
    assertEquals("alice", entity.getPrincipalId());
    assertEquals("jsh_prefix", entity.getTokenPrefix());
    assertEquals("HMAC-SHA256", entity.getTokenAlgorithm());
    assertEquals(1, entity.getTokenVersion());
    assertEquals("encoded", entity.getTokenHash());
    assertEquals("READ,UPDATE_REF", entity.getPermissionScopes());
    assertEquals(NOW, entity.getIssuedAt());
    assertEquals(NOW.plusSeconds(3600), entity.getExpiresAt());
    assertEquals(NOW.plusSeconds(30), entity.getLastUsedAt());
    assertEquals(NOW.plusSeconds(60), entity.getRevokedAt());
    assertEquals("admin", entity.getIssuedBy());
    assertEquals(0, entity.getEntityVersion());
    assertEquals(8, entity.getSecurityVersion());
  }

  @Test
  void identityAuditEntityExposesEveryMappedValueAndIsRegistered() {
    SecurityIdentityAuditEntity entity = new SecurityIdentityAuditEntity();
    entity.setAuditId("audit-1");
    entity.setOccurredAt(NOW);
    entity.setOperation(SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION);
    entity.setOutcome(SecurityAuditOutcome.DENIED);
    entity.setActorPrincipalId("admin");
    entity.setSubjectPrincipalId("alice");
    entity.setAuthenticationMethod("access_token");
    entity.setSessionId("session");
    entity.setCorrelationId("correlation");
    entity.setRemoteAddressHash("ab".repeat(32));
    entity.setCredentialKind(SecurityCredentialKind.ACCESS_TOKEN);
    entity.setCredentialId("token-1");
    entity.setReasonCode("ACCESS_TOKEN_REVOKED");
    entity.setFailureType(null);

    assertEquals("audit-1", entity.getAuditId());
    assertEquals(NOW, entity.getOccurredAt());
    assertEquals(SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION, entity.getOperation());
    assertEquals(SecurityAuditOutcome.DENIED, entity.getOutcome());
    assertEquals("admin", entity.getActorPrincipalId());
    assertEquals("alice", entity.getSubjectPrincipalId());
    assertEquals("access_token", entity.getAuthenticationMethod());
    assertEquals("session", entity.getSessionId());
    assertEquals("correlation", entity.getCorrelationId());
    assertEquals("ab".repeat(32), entity.getRemoteAddressHash());
    assertEquals(SecurityCredentialKind.ACCESS_TOKEN, entity.getCredentialKind());
    assertEquals("token-1", entity.getCredentialId());
    assertEquals("ACCESS_TOKEN_REVOKED", entity.getReasonCode());
    assertNull(entity.getFailureType());

    assertTrue(SecurityEntities.annotatedClasses().contains(SecurityLocalCredentialEntity.class));
    assertTrue(SecurityEntities.annotatedClasses().contains(SecurityAccessTokenEntity.class));
    assertTrue(SecurityEntities.annotatedClasses().contains(SecurityIdentityAuditEntity.class));
  }
}
