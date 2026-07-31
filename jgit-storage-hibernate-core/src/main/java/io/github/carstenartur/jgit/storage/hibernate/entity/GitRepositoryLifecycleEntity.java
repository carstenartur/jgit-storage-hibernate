/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
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
 * Durable existence row for one logical Hibernate-backed JGit repository.
 *
 * <p>Pack rows reference this identity with database-level cascading deletion. The separate
 * {@link GitRepositoryLockEntity} remains the short-lived write-coordination row, so foreign-key
 * checks performed while staging large payloads do not contend with publication and ref locks.
 */
@Entity
@Table(name = "git_repository_lifecycle")
public class GitRepositoryLifecycleEntity {

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
