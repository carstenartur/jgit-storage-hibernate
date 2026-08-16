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
import jakarta.persistence.Version;
import java.time.Instant;

/** Mutable local password verifier and lockout state for one stable principal. */
@Entity(name = "SecurityLocalCredential")
@Table(
    name = "git_security_local_credential",
    indexes = {
      @Index(name = "idx_git_sec_local_credential_locked", columnList = "locked_until")
    })
public class SecurityLocalCredentialEntity {

  @Id
  @Column(name = "principal_id", nullable = false, length = 128)
  private String principalId;

  @Column(name = "password_algorithm", nullable = false, length = 64)
  private String passwordAlgorithm;

  @Column(name = "password_version", nullable = false)
  private int passwordVersion;

  @Column(name = "password_hash", nullable = false, length = 2048)
  private String passwordHash;

  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  @Column(name = "failed_attempt_count", nullable = false)
  private int failedAttemptCount;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  @Column(name = "security_version", nullable = false)
  private long securityVersion;

  public String getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(String principalId) {
    this.principalId = principalId;
  }

  public String getPasswordAlgorithm() {
    return passwordAlgorithm;
  }

  public void setPasswordAlgorithm(String passwordAlgorithm) {
    this.passwordAlgorithm = passwordAlgorithm;
  }

  public int getPasswordVersion() {
    return passwordVersion;
  }

  public void setPasswordVersion(int passwordVersion) {
    this.passwordVersion = passwordVersion;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public Instant getChangedAt() {
    return changedAt;
  }

  public void setChangedAt(Instant changedAt) {
    this.changedAt = changedAt;
  }

  public int getFailedAttemptCount() {
    return failedAttemptCount;
  }

  public void setFailedAttemptCount(int failedAttemptCount) {
    this.failedAttemptCount = failedAttemptCount;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public void setLockedUntil(Instant lockedUntil) {
    this.lockedUntil = lockedUntil;
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
