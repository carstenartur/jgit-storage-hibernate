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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/** Stable authorization group persisted by the Security capability. */
@Entity(name = "SecurityGroup")
@Table(
    name = "git_security_group",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_git_sec_group_name", columnNames = "group_name"))
public class SecurityGroupEntity {

  @Id
  @Column(name = "group_id", nullable = false, length = 128)
  private String groupId;

  @Column(name = "group_name", nullable = false, length = 256)
  private String groupName;

  @Column(name = "description", length = 1024)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private SecurityGroupStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  @Column(name = "security_version", nullable = false)
  private long securityVersion;

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public String getGroupName() {
    return groupName;
  }

  public void setGroupName(String groupName) {
    this.groupName = groupName;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public SecurityGroupStatus getStatus() {
    return status;
  }

  public void setStatus(SecurityGroupStatus status) {
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
