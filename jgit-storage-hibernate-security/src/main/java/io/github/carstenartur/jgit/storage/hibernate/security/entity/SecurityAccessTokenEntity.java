/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/** One-way access-token verifier and lifecycle state; plaintext token values are never persisted. */
@Entity(name = "SecurityAccessToken")
@Table(
    name = "git_security_access_token",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_git_sec_access_token_prefix",
            columnNames = "token_prefix"),
    indexes = {
      @Index(
          name = "idx_git_sec_access_token_principal",
          columnList = "principal_id, issued_at"),
      @Index(
          name = "idx_git_sec_access_token_expiry",
          columnList = "expires_at, revoked_at")
    })
public class SecurityAccessTokenEntity {

  @Id
  @Column(name = "token_id", nullable = false, length = 128)
  private String tokenId;

  @Column(name = "principal_id", nullable = false, length = 128)
  private String principalId;

  @Column(name = "token_prefix", nullable = false, length = 64)
  private String tokenPrefix;

  @Column(name = "token_algorithm", nullable = false, length = 64)
  private String tokenAlgorithm;

  @Column(name = "token_version", nullable = false)
  private int tokenVersion;

  @Column(name = "token_hash", nullable = false, length = 512)
  private String tokenHash;

  @Column(name = "permission_scopes", nullable = false, length = 512)
  private String permissionScopes;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "issued_by", nullable = false, length = 128)
  private String issuedBy;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  @Column(name = "security_version", nullable = false)
  private long securityVersion;

  public String getTokenId() {
    return tokenId;
  }

  public void setTokenId(String tokenId) {
    this.tokenId = tokenId;
  }

  public String getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(String principalId) {
    this.principalId = principalId;
  }

  public String getTokenPrefix() {
    return tokenPrefix;
  }

  public void setTokenPrefix(String tokenPrefix) {
    this.tokenPrefix = tokenPrefix;
  }

  public String getTokenAlgorithm() {
    return tokenAlgorithm;
  }

  public void setTokenAlgorithm(String tokenAlgorithm) {
    this.tokenAlgorithm = tokenAlgorithm;
  }

  public int getTokenVersion() {
    return tokenVersion;
  }

  public void setTokenVersion(int tokenVersion) {
    this.tokenVersion = tokenVersion;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public String getPermissionScopes() {
    return permissionScopes;
  }

  public void setPermissionScopes(String permissionScopes) {
    this.permissionScopes = permissionScopes;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public void setIssuedAt(Instant issuedAt) {
    this.issuedAt = issuedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public String getIssuedBy() {
    return issuedBy;
  }

  public void setIssuedBy(String issuedBy) {
    this.issuedBy = issuedBy;
  }

  public long getEntityVersion() {
    return entityVersion;
  }

  public long getSecurityVersion() {
    return securityVersion;
  }

  public void setSecurityVersion(long securityVersion) {
    this.securityVersion = securityVersion;
  }
}
