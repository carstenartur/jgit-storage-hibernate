/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security.entity;

import io.github.carstenartur.jgit.storage.hibernate.security.RepositoryPolicyOwnershipMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/** Active version/digest and actor evidence for one externally managed repository policy source. */
@Entity(name = "SecurityManagedPolicy")
@Table(
    name = "git_security_managed_policy",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_git_sec_managed_policy_source",
            columnNames = {"repository_name", "managed_source_id", "managed_source_instance_id"}),
    indexes =
        @Index(
            name = "idx_git_sec_managed_policy_repository",
            columnList = "repository_name, policy_generation"))
public class SecurityManagedPolicyEntity {

  @Id
  @Column(name = "policy_id", nullable = false, length = 128)
  private String policyId;

  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Column(name = "managed_source_id", nullable = false, length = 128)
  private String managedSourceId;

  @Column(name = "managed_source_instance_id", nullable = false, length = 128)
  private String managedSourceInstanceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "ownership_mode", nullable = false, length = 32)
  private RepositoryPolicyOwnershipMode ownershipMode;

  @Column(name = "policy_version", nullable = false)
  private long policyVersion;

  @Column(name = "content_digest", nullable = false, length = 64)
  private String contentDigest;

  @Column(name = "policy_generation", nullable = false)
  private long policyGeneration;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by_principal_id", nullable = false, length = 128)
  private String createdByPrincipalId;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by_principal_id", nullable = false, length = 128)
  private String updatedByPrincipalId;

  @Column(name = "last_operation_id", nullable = false, length = 256)
  private String lastOperationId;

  @Column(name = "last_correlation_id", nullable = false, length = 256)
  private String lastCorrelationId;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  public String getPolicyId() {
    return policyId;
  }

  public void setPolicyId(String policyId) {
    this.policyId = policyId;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getManagedSourceId() {
    return managedSourceId;
  }

  public void setManagedSourceId(String managedSourceId) {
    this.managedSourceId = managedSourceId;
  }

  public String getManagedSourceInstanceId() {
    return managedSourceInstanceId;
  }

  public void setManagedSourceInstanceId(String managedSourceInstanceId) {
    this.managedSourceInstanceId = managedSourceInstanceId;
  }

  public RepositoryPolicyOwnershipMode getOwnershipMode() {
    return ownershipMode;
  }

  public void setOwnershipMode(RepositoryPolicyOwnershipMode ownershipMode) {
    this.ownershipMode = ownershipMode;
  }

  public long getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(long policyVersion) {
    this.policyVersion = policyVersion;
  }

  public String getContentDigest() {
    return contentDigest;
  }

  public void setContentDigest(String contentDigest) {
    this.contentDigest = contentDigest;
  }

  public long getPolicyGeneration() {
    return policyGeneration;
  }

  public void setPolicyGeneration(long policyGeneration) {
    this.policyGeneration = policyGeneration;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getCreatedByPrincipalId() {
    return createdByPrincipalId;
  }

  public void setCreatedByPrincipalId(String createdByPrincipalId) {
    this.createdByPrincipalId = createdByPrincipalId;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getUpdatedByPrincipalId() {
    return updatedByPrincipalId;
  }

  public void setUpdatedByPrincipalId(String updatedByPrincipalId) {
    this.updatedByPrincipalId = updatedByPrincipalId;
  }

  public String getLastOperationId() {
    return lastOperationId;
  }

  public void setLastOperationId(String lastOperationId) {
    this.lastOperationId = lastOperationId;
  }

  public String getLastCorrelationId() {
    return lastCorrelationId;
  }

  public void setLastCorrelationId(String lastCorrelationId) {
    this.lastCorrelationId = lastCorrelationId;
  }

  public long getEntityVersion() {
    return entityVersion;
  }
}
