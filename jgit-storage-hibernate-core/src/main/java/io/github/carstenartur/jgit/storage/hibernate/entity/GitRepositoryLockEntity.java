/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Nationalized;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Repository-scoped coordination row used for cross-SessionFactory ref-update serialization.
 *
 * <p>The row is locked pessimistically for the short transaction that refreshes and applies a ref
 * update. It contains no Git state; refs and objects remain stored in Reftables and pack rows. Its
 * lifecycle is owned by the separate durable repository identity, allowing payload staging to
 * reference repository existence without locking this coordination row.
 */
@Entity
@Table(name = "git_repository_lock")
public class GitRepositoryLockEntity {

  @Id
  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_name",
      referencedColumnName = "repository_name",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_repository_lock_lifecycle"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private GitRepositoryLifecycleEntity lifecycle;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public GitRepositoryLifecycleEntity getLifecycle() {
    return lifecycle;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
