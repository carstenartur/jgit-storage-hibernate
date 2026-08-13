/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security.entity;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only persistent evidence for one repository authorization decision. */
@Entity(name = "SecurityAccessAudit")
@Table(
    name = "git_security_access_audit",
    indexes = {
      @Index(
          name = "idx_git_sec_audit_repository",
          columnList = "repository_name, occurred_at"),
      @Index(
          name = "idx_git_sec_audit_principal",
          columnList = "principal_id, occurred_at"),
      @Index(
          name = "idx_git_sec_audit_correlation",
          columnList = "correlation_id, occurred_at"),
      @Index(name = "idx_git_sec_audit_outcome", columnList = "outcome_name, occurred_at")
    })
public class SecurityAccessAuditEntity {

  @Id
  @Column(name = "audit_id", nullable = false, length = 64)
  private String auditId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "principal_id", nullable = false, length = 128)
  private String principalId;

  @Column(name = "authentication_method", nullable = false, length = 256)
  private String authenticationMethod;

  @Column(name = "session_id", nullable = false, length = 256)
  private String sessionId;

  @Column(name = "correlation_id", nullable = false, length = 256)
  private String correlationId;

  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_name", nullable = false, length = 32)
  private RepositoryAccessOperation operation;

  @Column(name = "ref_name", length = 1024)
  private String refName;

  @Column(name = "old_object_id", length = 64)
  private String oldObjectId;

  @Column(name = "new_object_id", length = 64)
  private String newObjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome_name", nullable = false, length = 16)
  private SecurityAuditOutcome outcome;

  @Column(name = "reason_code", nullable = false, length = 128)
  private String reasonCode;

  @Column(name = "evidence_id", length = 256)
  private String evidenceId;

  @Column(name = "policy_version", nullable = false)
  private long policyVersion;

  @Column(name = "failure_type", length = 256)
  private String failureType;

  public String getAuditId() {
    return auditId;
  }

  public void setAuditId(String auditId) {
    this.auditId = auditId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant occurredAt) {
    this.occurredAt = occurredAt;
  }

  public String getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(String principalId) {
    this.principalId = principalId;
  }

  public String getAuthenticationMethod() {
    return authenticationMethod;
  }

  public void setAuthenticationMethod(String authenticationMethod) {
    this.authenticationMethod = authenticationMethod;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public RepositoryAccessOperation getOperation() {
    return operation;
  }

  public void setOperation(RepositoryAccessOperation operation) {
    this.operation = operation;
  }

  public String getRefName() {
    return refName;
  }

  public void setRefName(String refName) {
    this.refName = refName;
  }

  public String getOldObjectId() {
    return oldObjectId;
  }

  public void setOldObjectId(String oldObjectId) {
    this.oldObjectId = oldObjectId;
  }

  public String getNewObjectId() {
    return newObjectId;
  }

  public void setNewObjectId(String newObjectId) {
    this.newObjectId = newObjectId;
  }

  public SecurityAuditOutcome getOutcome() {
    return outcome;
  }

  public void setOutcome(SecurityAuditOutcome outcome) {
    this.outcome = outcome;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(String reasonCode) {
    this.reasonCode = reasonCode;
  }

  public String getEvidenceId() {
    return evidenceId;
  }

  public void setEvidenceId(String evidenceId) {
    this.evidenceId = evidenceId;
  }

  public long getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(long policyVersion) {
    this.policyVersion = policyVersion;
  }

  public String getFailureType() {
    return failureType;
  }

  public void setFailureType(String failureType) {
    this.failureType = failureType;
  }
}
