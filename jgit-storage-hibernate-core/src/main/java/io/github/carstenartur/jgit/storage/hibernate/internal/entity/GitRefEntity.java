/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Nationalized;

/** Optional normalized Git ref row for query and future non-reftable storage strategies. */
@Entity
@Table(
    name = "git_refs",
    indexes = {
      @Index(name = "idx_ref_repo", columnList = "repository_name"),
      @Index(name = "idx_ref_repo_name", columnList = "repository_name, ref_name", unique = true)
    })
public class GitRefEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Version
  @Column(name = "version")
  private Long version;

  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Nationalized
  @Column(name = "ref_name", nullable = false, length = 1024)
  private String refName;

  @Column(name = "object_id", length = 64)
  private String objectId;

  @Column(name = "symbolic_target", length = 1024)
  private String symbolicTarget;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getVersion() { return version; }
  public void setVersion(Long version) { this.version = version; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getRefName() { return refName; }
  public void setRefName(String refName) { this.refName = refName; }
  public String getObjectId() { return objectId; }
  public void setObjectId(String objectId) { this.objectId = objectId; }
  public String getSymbolicTarget() { return symbolicTarget; }
  public void setSymbolicTarget(String symbolicTarget) { this.symbolicTarget = symbolicTarget; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
