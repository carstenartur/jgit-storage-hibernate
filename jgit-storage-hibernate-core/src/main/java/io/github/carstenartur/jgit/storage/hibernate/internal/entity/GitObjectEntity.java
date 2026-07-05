/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

/** Optional loose Git object row used for object-count projections and future storage strategies. */
@Entity
@Table(
    name = "git_objects",
    indexes = {
      @Index(name = "idx_git_obj_repo_sha", columnList = "repository_name, object_id", unique = true),
      @Index(name = "idx_git_obj_type", columnList = "object_type")
    })
public class GitObjectEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "object_id", length = 64, nullable = false)
  private String objectId;

  @Column(name = "object_type", nullable = false)
  private int objectType;

  @Column(name = "object_size", nullable = false)
  private long objectSize;

  @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
  @Basic(fetch = FetchType.LAZY)
  @Column(name = "data", nullable = false)
  private byte[] data;

  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getObjectId() { return objectId; }
  public void setObjectId(String objectId) { this.objectId = objectId; }
  public int getObjectType() { return objectType; }
  public void setObjectType(int objectType) { this.objectType = objectType; }
  public long getObjectSize() { return objectSize; }
  public void setObjectSize(long objectSize) { this.objectSize = objectSize; }
  public byte[] getData() { return data; }
  public void setData(byte[] data) { this.data = data; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
