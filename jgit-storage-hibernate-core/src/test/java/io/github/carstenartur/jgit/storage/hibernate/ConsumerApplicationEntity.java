/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test-only application entity registered alongside the generic JGit storage mappings. */
@Entity
@Table(name = "consumer_application_entity")
public class ConsumerApplicationEntity {

  @Id
  @Column(name = "entity_id", nullable = false, length = 128)
  private String id;

  @Column(name = "entity_status", nullable = false, length = 255)
  private String status;

  protected ConsumerApplicationEntity() {}

  public ConsumerApplicationEntity(String id, String status) {
    this.id = id;
    this.status = status;
  }

  public String getId() {
    return id;
  }

  public String getStatus() {
    return status;
  }
}
