/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import java.time.Instant;
import java.util.Objects;

/**
 * Bounded compound query over the generic Git commit projection.
 *
 * @param repositoryName logical repository name
 * @param text optional simple-query-string expression over messages, paths and changed text
 * @param authorEmail optional exact author email
 * @param pathText optional analyzed path expression whose terms must all match
 * @param from optional inclusive lower commit-time bound
 * @param to optional inclusive upper commit-time bound
 * @param limit positive maximum hit count
 */
public record GitHistoryQuery(
    String repositoryName,
    String text,
    String authorEmail,
    String pathText,
    Instant from,
    Instant to,
    int limit) {

  public GitHistoryQuery {
    repositoryName = requireNotBlank(repositoryName, "repositoryName");
    text = normalizeOptional(text);
    authorEmail = normalizeOptional(authorEmail);
    pathText = normalizeOptional(pathText);
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be greater than zero");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
  }

  /** Returns whether a relevance-scored free-text predicate is present. */
  public boolean hasText() {
    return text != null;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
