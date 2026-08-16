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

/** Persistent deterministic protected-ref rule. */
@Entity(name = "SecurityRefRule")
@Table(
    name = "git_security_ref_rule",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_git_sec_ref_rule",
            columnNames = {"repository_name", "rule_id"}),
    indexes = {
      @Index(
          name = "idx_git_sec_ref_rule_repository",
          columnList = "repository_name, permission_name, priority"),
      @Index(
          name = "idx_git_sec_ref_rule_subject",
          columnList = "subject_type, subject_id"),
      @Index(
          name = "idx_git_sec_ref_rule_managed_source",
          columnList =
              "repository_name, managed_source_id, managed_source_instance_id, managed_entry_key")
    })
public class SecurityRefRuleEntity {

  @Id
  @Column(name = "rule_id", nullable = false, length = 128)
  private String ruleId;

  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Column(name = "ref_pattern", nullable = false, length = 1024)
  private String refPattern;

  @Enumerated(EnumType.STRING)
  @Column(name = "permission_name", nullable = false, length = 32)
  private GitRepositoryPermission permission;

  @Enumerated(EnumType.STRING)
  @Column(name = "effect_name", nullable = false, length = 16)
  private SecurityEffect effect;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", length = 32)
  private SecuritySubjectType subjectType;

  @Column(name = "subject_id", length = 128)
  private String subjectId;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

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

  public String getRuleId() {
    return ruleId;
  }

  public void setRuleId(String ruleId) {
    this.ruleId = ruleId;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getRefPattern() {
    return refPattern;
  }

  public void setRefPattern(String refPattern) {
    this.refPattern = refPattern;
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

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
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

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
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
