/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Objects;

/** Logical repository name used to partition database-backed Git storage. */
public record RepositoryName(String value) {

  /**
   * Create a repository name.
   *
   * @param value non-blank logical repository name
   */
  public RepositoryName {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("repository name must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
