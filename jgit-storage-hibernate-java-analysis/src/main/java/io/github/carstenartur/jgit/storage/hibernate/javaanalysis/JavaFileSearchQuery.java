/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Map;

/**
 * Literal analyzed text with exact repository/module/file-type filtering before ranking and paging.
 * Blank module/fileType omit that filter. Text is never parsed as a query language.
 */
public record JavaFileSearchQuery(
    String repositoryName, String module, String fileType, String literalText,
    Map<JavaFileSearchField, Float> boosts, int offset, int limit) {

  public static final int MAX_PAGE_SIZE = 1_000;

  public JavaFileSearchQuery {
    JavaFileSearchDocument.requireText(repositoryName, "repositoryName");
    module = module == null || module.isBlank() ? null : module;
    fileType = fileType == null || fileType.isBlank() ? null : fileType;
    literalText = literalText == null ? "" : literalText;
    boosts = Map.copyOf(boosts);
    if (boosts.isEmpty() || boosts.values().stream().anyMatch(boost -> !Float.isFinite(boost) || boost <= 0)) {
      throw new IllegalArgumentException("At least one field with a finite positive boost is required");
    }
    if (offset < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("offset must be nonnegative and limit between 1 and " + MAX_PAGE_SIZE);
    }
  }
}
