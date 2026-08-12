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
import java.time.Instant;

/** Principal membership in one stable authorization group. */
@Entity(name = "SecurityGroupMembership")
@Table(
    name = "git_security_group_member",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_git_sec_group_member",
            columnNames = {"group_id", "principal_id"}),
    indexes = {
      @Index(name = "idx_git_sec_member_principal", columnList = "principal_id"),
      @Index(name = "idx_git_sec_member_group", columnList = "group_id")
    })
public class SecurityGroupMembershipEntity {

  @Id
  @Column(name = "membership_id", nullable = false, length = 128)
  private String membershipId;

  @Column(name = "group_id", nullable = false, length = 128)
  private String groupId;

  @Column(name = "principal_id", nullable = false, length = 128)
  private String principalId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, length = 128)
  private String createdBy;

  @Column(name = "security_version", nullable = false)
  private long securityVersion;

  public String getMembershipId() {
    return membershipId;
  }

  public void setMembershipId(String membershipId) {
    this.membershipId = membershipId;
  }

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public String getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(String principalId) {
    this.principalId = principalId;
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

  public long getSecurityVersion() {
    return securityVersion;
  }

  public void setSecurityVersion(long securityVersion) {
    this.securityVersion = securityVersion;
  }
}
