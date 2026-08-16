/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security.entity;

import io.github.carstenartur.jgit.storage.hibernate.security.GitRepositoryPermission;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityEffect;
import io.github.carstenartur.jgit.storage.hibernate.security.SecuritySubjectType;
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

/** Persistent principal/group permission for one immutable logical repository name. */
@Entity(name = "SecurityRepositoryGrant")
@Table(
    name = "git_security_repository_grant",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_git_sec_repository_grant",
            columnNames = {
              "repository_name", "subject_type", "subject_id", "permission_name", "effect_name"
            }),
    indexes = {
      @Index(
          name = "idx_git_sec_grant_subject",
          columnList = "subject_type, subject_id, repository_name"),
      @Index(
          name = "idx_git_sec_grant_repository",
          columnList = "repository_name, permission_name"),
      @Index(
          name = "idx_git_sec_grant_managed_source",
          columnList =
              "repository_name, managed_source_id, managed_source_instance_id, managed_entry_key")
    })
public class SecurityRepositoryGrantEntity {

  @Id
  @Column(name = "grant_id", nullable = false, length = 128)
  private String grantId;

  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 32)
  private SecuritySubjectType subjectType;

  @Column(name = "subject_id", nullable = false, length = 128)
  private String subjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "permission_name", nullable = false, length = 32)
  private GitRepositoryPermission permission;

  @Enumerated(EnumType.STRING)
  @Column(name = "effect_name", nullable = false, length = 16)
  private SecurityEffect effect;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, length = 128)
  private String createdBy;

  @Column(name = "managed_source_id", length = 128)
  private String managedSourceId;

  @Column(name = "managed_source_instance_id", length = 128)
  private String managedSourceInstanceId;

  @Column(name = "managed_entry_key", length = 256)
  private String managedEntryKey;

  @Column(name = "managed_policy_version")
  private Long managedPolicyVersion;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  @Column(name = "security_version", nullable = false)
  private long securityVersion;

  public String getGrantId() {
    return grantId;
  }

  public void setGrantId(String grantId) {
    this.grantId = grantId;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public SecuritySubjectType getSubjectType() {
    return subjectType;
  }

  public void setSubjectType(SecuritySubjectType subjectType) {
    this.subjectType = subjectType;
  }

  public String getSubjectId() {
    return subjectId;
  }

  public void setSubjectId(String subjectId) {
    this.subjectId = subjectId;
  }

  public GitRepositoryPermission getPermission() {
    return permission;
  }

  public void setPermission(GitRepositoryPermission permission) {
    this.permission = permission;
  }

  public SecurityEffect getEffect() {
    return effect;
  }

  public void setEffect(SecurityEffect effect) {
    this.effect = effect;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
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

  public String getManagedEntryKey() {
    return managedEntryKey;
  }

  public void setManagedEntryKey(String managedEntryKey) {
    this.managedEntryKey = managedEntryKey;
  }

  public Long getManagedPolicyVersion() {
    return managedPolicyVersion;
  }

  public void setManagedPolicyVersion(Long managedPolicyVersion) {
    this.managedPolicyVersion = managedPolicyVersion;
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
