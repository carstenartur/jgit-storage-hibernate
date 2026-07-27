/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Nationalized;

/**
 * Repository-scoped coordination row used for cross-SessionFactory ref-update serialization.
 *
 * <p>The row is locked pessimistically for the short transaction that refreshes and applies a ref
 * update. It contains no Git state; refs and objects remain stored in Reftables and pack rows.
 */
@Entity
@Table(name = "git_repository_lock")
public class GitRepositoryLockEntity {

  @Id
  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
