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
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/** Entity representing one persisted pack-related file, such as PACK, IDX or REFTABLE. */
@Entity
@Table(
    name = "git_packs",
    indexes = {
      @Index(name = "idx_pack_repo_committed", columnList = "repository_name, committed"),
      @Index(
          name = "idx_pack_repo_lease",
          columnList = "repository_name, committed, write_lease_until")
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

  /**
   * Durable repository identity owning this pack-related file.
   *
   * <p>The scalar repository name remains the write-facing mapping. This read-only association makes
   * Hibernate-generated schemas enforce the same lifecycle and cascading-delete contract as the
   * versioned migrations, without coupling payload staging to the independent publication lock row.
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_name",
      referencedColumnName = "repository_name",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_pack_repository_lifecycle"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private GitRepositoryLifecycleEntity repositoryLifecycle;

  @Nationalized
  @Column(name = "pack_name", nullable = false, length = 255)
  private String packName;

  @Column(name = "pack_extension", nullable = false, length = 32)
  private String packExtension;

  /**
   * Legacy inline payload.
   *
   * <p>Rows written by the chunked storage path leave this column {@code null} and store their
   * payload in {@code git_pack_chunks}. Keeping the column allows existing installations to be
   * upgraded without rewriting every already published pack.
   */
  @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
  @Basic(fetch = FetchType.LAZY)
  @Column(name = "data")
  private byte[] data;

  @OneToMany(mappedBy = "pack", cascade = CascadeType.ALL, orphanRemoval = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private List<GitPackChunkEntity> chunks = new ArrayList<>();

  @Column(name = "file_size", nullable = false)
  private long fileSize;

  @Column(name = "committed", nullable = false)
  private boolean committed;

  @Column(name = "write_token", length = 36)
  private String writeToken;

  @Column(name = "write_lease_until")
  private Instant writeLeaseUntil;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "committed_at")
  private Instant committedAt;

  @Column(name = "pack_source", length = 32)
  private String packSource;

  @Column(name = "last_modified")
  private Long lastModified;

  @Column(name = "object_count")
  private Long objectCount;

  @Column(name = "delta_count")
  private Long deltaCount;

  @Column(name = "index_version")
  private Integer indexVersion;

  @Column(name = "min_update_index")
  private Long minUpdateIndex;

  @Column(name = "max_update_index")
  private Long maxUpdateIndex;

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

  public GitRepositoryLifecycleEntity getRepositoryLifecycle() {
    return repositoryLifecycle;
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

  public List<GitPackChunkEntity> getChunks() {
    return chunks;
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

  public String getWriteToken() {
    return writeToken;
  }

  public void setWriteToken(String writeToken) {
    this.writeToken = writeToken;
  }

  public Instant getWriteLeaseUntil() {
    return writeLeaseUntil;
  }

  public void setWriteLeaseUntil(Instant writeLeaseUntil) {
    this.writeLeaseUntil = writeLeaseUntil;
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

  public String getPackSource() {
    return packSource;
  }

  public void setPackSource(String packSource) {
    this.packSource = packSource;
  }

  public Long getLastModified() {
    return lastModified;
  }

  public void setLastModified(Long lastModified) {
    this.lastModified = lastModified;
  }

  public Long getObjectCount() {
    return objectCount;
  }

  public void setObjectCount(Long objectCount) {
    this.objectCount = objectCount;
  }

  public Long getDeltaCount() {
    return deltaCount;
  }

  public void setDeltaCount(Long deltaCount) {
    this.deltaCount = deltaCount;
  }

  public Integer getIndexVersion() {
    return indexVersion;
  }

  public void setIndexVersion(Integer indexVersion) {
    this.indexVersion = indexVersion;
  }

  public Long getMinUpdateIndex() {
    return minUpdateIndex;
  }

  public void setMinUpdateIndex(Long minUpdateIndex) {
    this.minUpdateIndex = minUpdateIndex;
  }

  public Long getMaxUpdateIndex() {
    return maxUpdateIndex;
  }

  public void setMaxUpdateIndex(Long maxUpdateIndex) {
    this.maxUpdateIndex = maxUpdateIndex;
  }
}
