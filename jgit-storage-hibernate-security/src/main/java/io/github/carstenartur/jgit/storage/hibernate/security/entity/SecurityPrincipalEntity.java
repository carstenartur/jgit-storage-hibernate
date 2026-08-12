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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Stable principal identity persisted by the Security capability. */
@Entity(name = "SecurityPrincipal")
@Table(name = "git_security_principal")
public class SecurityPrincipalEntity {

  @Id
  @Column(name = "principal_id", nullable = false, length = 128)
  private String principalId;

  @Enumerated(EnumType.STRING)
  @Column(name = "principal_type", nullable = false, length = 32)
  private SecurityPrincipalType principalType;

  @Column(name = "login_name", length = 256)
  private String loginName;

  @Column(name = "display_name", length = 256)
  private String displayName;

  @Column(name = "external_issuer", length = 512)
  private String externalIssuer;

  @Column(name = "external_subject", length = 512)
  private String externalSubject;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private SecurityPrincipalStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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

  public SecurityPrincipalType getPrincipalType() {
    return principalType;
  }

  public void setPrincipalType(SecurityPrincipalType principalType) {
    this.principalType = principalType;
  }

  public String getLoginName() {
    return loginName;
  }

  public void setLoginName(String loginName) {
    this.loginName = loginName;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getExternalIssuer() {
    return externalIssuer;
  }

  public void setExternalIssuer(String externalIssuer) {
    this.externalIssuer = externalIssuer;
  }

  public String getExternalSubject() {
    return externalSubject;
  }

  public void setExternalSubject(String externalSubject) {
    this.externalSubject = externalSubject;
  }

  public SecurityPrincipalStatus getStatus() {
    return status;
  }

  public void setStatus(SecurityPrincipalStatus status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
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
