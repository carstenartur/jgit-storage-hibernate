/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

/** Entity representing one persisted pack-related file, such as PACK, IDX or REFTABLE. */
@Entity
@Table(
    name = "git_packs",
    indexes = {
      @Index(name = "idx_pack_repo", columnList = "repository_name"),
      @Index(name = "idx_pack_repo_name", columnList = "repository_name, pack_name"),
      @Index(name = "idx_pack_repo_committed", columnList = "repository_name, committed")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_pack_repo_name_ext",
          columnNames = {"repository_name", "pack_name", "pack_extension"})
    })
public class GitPackEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Nationalized
  @Column(name = "pack_name", nullable = false, length = 255)
  private String packName;

  @Column(name = "pack_extension", nullable = false, length = 32)
  private String packExtension;

  @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
  @Basic(fetch = FetchType.LAZY)
  @Column(name = "data", nullable = false)
  private byte[] data;

  @Column(name = "file_size", nullable = false)
  private long fileSize;

  @Column(name = "committed", nullable = false)
  private boolean committed;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "committed_at")
  private Instant committedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getPackName() {
    return packName;
  }

  public void setPackName(String packName) {
    this.packName = packName;
  }

  public String getPackExtension() {
    return packExtension;
  }

  public void setPackExtension(String packExtension) {
    this.packExtension = packExtension;
  }

  public byte[] getData() {
    return data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  public long getFileSize() {
    return fileSize;
  }

  public void setFileSize(long fileSize) {
    this.fileSize = fileSize;
  }

  public boolean isCommitted() {
    return committed;
  }

  public void setCommitted(boolean committed) {
    this.committed = committed;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getCommittedAt() {
    return committedAt;
  }

  public void setCommittedAt(Instant committedAt) {
    this.committedAt = committedAt;
  }
}
