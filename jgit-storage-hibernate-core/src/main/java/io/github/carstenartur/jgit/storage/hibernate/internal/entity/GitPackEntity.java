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
import org.hibernate.type.SqlTypes;

/** Entity representing one Git pack-related file extension stored in the database. */
@Entity
@Table(
    name = "git_packs",
    indexes = {
      @Index(name = "idx_pack_repo", columnList = "repository_name"),
      @Index(name = "idx_pack_repo_name", columnList = "repository_name, pack_name"),
      @Index(name = "idx_pack_repo_name_ext", columnList = "repository_name, pack_name, pack_extension")
    })
public class GitPackEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "repository_name", nullable = false)
  private String repositoryName;

  @Column(name = "pack_name", nullable = false)
  private String packName;

  @Column(name = "pack_extension", nullable = false)
  private String packExtension;

  @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
  @Basic(fetch = FetchType.LAZY)
  @Column(name = "data", nullable = false)
  private byte[] data;

  @Column(name = "file_size", nullable = false)
  private long fileSize;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getPackName() { return packName; }
  public void setPackName(String packName) { this.packName = packName; }
  public String getPackExtension() { return packExtension; }
  public void setPackExtension(String packExtension) { this.packExtension = packExtension; }
  public byte[] getData() { return data; }
  public void setData(byte[] data) { this.data = data; }
  public long getFileSize() { return fileSize; }
  public void setFileSize(long fileSize) { this.fileSize = fileSize; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
