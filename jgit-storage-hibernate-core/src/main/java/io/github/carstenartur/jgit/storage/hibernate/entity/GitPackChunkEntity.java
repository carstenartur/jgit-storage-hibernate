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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One bounded binary chunk belonging to a pack-related file. */
@Entity
@Table(
    name = "git_pack_chunks",
    indexes = {@Index(name = "idx_pack_chunk_pack", columnList = "pack_id")},
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_pack_chunk_index",
          columnNames = {"pack_id", "chunk_index"})
    })
public class GitPackChunkEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "pack_id", nullable = false)
  private Long packId;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
  @Basic(fetch = FetchType.LAZY)
  @Column(name = "chunk_data", nullable = false)
  private byte[] data;

  @Column(name = "chunk_size", nullable = false)
  private int chunkSize;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getPackId() {
    return packId;
  }

  public void setPackId(Long packId) {
    this.packId = packId;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public void setChunkIndex(int chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public byte[] getData() {
    return data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  public int getChunkSize() {
    return chunkSize;
  }

  public void setChunkSize(int chunkSize) {
    this.chunkSize = chunkSize;
  }
}
