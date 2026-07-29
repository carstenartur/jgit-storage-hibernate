/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.entity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** Stable ORM identity of one ordered chunk inside a persisted pack extension. */
public final class GitPackChunkId implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  private Long packId;
  private int chunkIndex;

  /** Required by Jakarta Persistence. */
  public GitPackChunkId() {}

  public GitPackChunkId(Long packId, int chunkIndex) {
    this.packId = Objects.requireNonNull(packId, "packId");
    this.chunkIndex = chunkIndex;
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

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GitPackChunkId that)) {
      return false;
    }
    return chunkIndex == that.chunkIndex && Objects.equals(packId, that.packId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(packId, chunkIndex);
  }
}
