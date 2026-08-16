/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security.entity;

import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuditOutcome;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityCredentialKind;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityIdentityAuditOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Append-only persistent evidence for credential lifecycle and authentication operations. */
@Entity(name = "SecurityIdentityAudit")
@Immutable
@Table(
    name = "git_security_identity_audit",
    indexes = {
      @Index(
          name = "idx_git_sec_identity_audit_subject",
          columnList = "subject_principal_id, occurred_at"),
      @Index(
          name = "idx_git_sec_identity_audit_actor",
          columnList = "actor_principal_id, occurred_at"),
      @Index(
          name = "idx_git_sec_identity_audit_correlation",
          columnList = "correlation_id, occurred_at"),
      @Index(
          name = "idx_git_sec_identity_audit_credential",
          columnList = "credential_id, occurred_at"),
      @Index(
          name = "idx_git_sec_identity_audit_operation",
          columnList = "operation_name, outcome_name, occurred_at")
    })
public class SecurityIdentityAuditEntity {

  @Id
  @Column(name = "audit_id", nullable = false, length = 64)
  private String auditId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_name", nullable = false, length = 32)
  private SecurityIdentityAuditOperation operation;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome_name", nullable = false, length = 16)
  private SecurityAuditOutcome outcome;

  @Column(name = "actor_principal_id", length = 128)
  private String actorPrincipalId;

  @Column(name = "subject_principal_id", length = 128)
  private String subjectPrincipalId;

  @Column(name = "authentication_method", nullable = false, length = 256)
  private String authenticationMethod;

  @Column(name = "session_id", nullable = false, length = 256)
  private String sessionId;

  @Column(name = "correlation_id", nullable = false, length = 256)
  private String correlationId;

  @Column(name = "remote_address_hash", length = 64)
  private String remoteAddressHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "credential_kind", nullable = false, length = 32)
  private SecurityCredentialKind credentialKind;

  @Column(name = "credential_id", length = 128)
  private String credentialId;

  @Column(name = "reason_code", nullable = false, length = 128)
  private String reasonCode;

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

  public SecurityIdentityAuditOperation getOperation() {
    return operation;
  }

  public void setOperation(SecurityIdentityAuditOperation operation) {
    this.operation = operation;
  }

  public SecurityAuditOutcome getOutcome() {
    return outcome;
  }

  public void setOutcome(SecurityAuditOutcome outcome) {
    this.outcome = outcome;
  }

  public String getActorPrincipalId() {
    return actorPrincipalId;
  }

  public void setActorPrincipalId(String actorPrincipalId) {
    this.actorPrincipalId = actorPrincipalId;
  }

  public String getSubjectPrincipalId() {
    return subjectPrincipalId;
  }

  public void setSubjectPrincipalId(String subjectPrincipalId) {
    this.subjectPrincipalId = subjectPrincipalId;
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

  public String getRemoteAddressHash() {
    return remoteAddressHash;
  }

  public void setRemoteAddressHash(String remoteAddressHash) {
    this.remoteAddressHash = remoteAddressHash;
  }

  public SecurityCredentialKind getCredentialKind() {
    return credentialKind;
  }

  public void setCredentialKind(SecurityCredentialKind credentialKind) {
    this.credentialKind = credentialKind;
  }

  public String getCredentialId() {
    return credentialId;
  }

  public void setCredentialId(String credentialId) {
    this.credentialId = credentialId;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(String reasonCode) {
    this.reasonCode = reasonCode;
  }

  public String getFailureType() {
    return failureType;
  }

  public void setFailureType(String failureType) {
    this.failureType = failureType;
  }
}
