/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Monotonic invalidation evidence for one principal, repository or global security scope. */
@Entity(name = "SecurityVersion")
@Table(name = "git_security_version")
public class SecurityVersionEntity {

  @Id
  @Column(name = "scope_key", nullable = false, length = 512)
  private String scopeKey;

  @Column(name = "version_value", nullable = false)
  private long versionValue;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  public String getScopeKey() {
    return scopeKey;
  }

  public void setScopeKey(String scopeKey) {
    this.scopeKey = scopeKey;
  }

  public long getVersionValue() {
    return versionValue;
  }

  public void setVersionValue(long versionValue) {
    this.versionValue = versionValue;
  }

  public long getEntityVersion() {
    return entityVersion;
  }
}
