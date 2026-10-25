/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One historical file document. Identity is (repositoryName, documentId); callers may use blobId
 * to deduplicate identical content. The first supplied identity retains all its provenance and fields.
 * Paths must be nonempty and retain whitespace exactly, including whitespace-only Git filenames.
 * Qualified-name facets are single whole strings, including any embedded newlines. Exact metadata
 * and normalized qualified facets must fit {@link #MAX_KEYWORD_UTF8_BYTES}; oversized input is
 * rejected before index construction, without splitting or truncating a whole-value facet.
 */
public record JavaFileSearchDocument(
    String repositoryName, String documentId, String commitId, String blobId, String path,
    String module, String fileType, Map<JavaFileSearchField, String> fields) {

  /** Maximum UTF-8 byte length of an indexed keyword in this analysis profile. */
  public static final int MAX_KEYWORD_UTF8_BYTES = 32_766;

  public JavaFileSearchDocument {
    requireText(repositoryName, "repositoryName");
    requireText(documentId, "documentId");
    requireText(commitId, "commitId");
    requireText(blobId, "blobId");
    if (path == null || path.isEmpty()) {
      throw new IllegalArgumentException("path must not be empty");
    }
    module = module == null ? "" : module;
    fileType = fileType == null ? "" : fileType;
    fields = Map.copyOf(fields);
    requireKeywordSize(repositoryName, "repositoryName");
    requireKeywordSize(documentId, "documentId");
    requireKeywordSize(module, "module");
    requireKeywordSize(fileType, "fileType");
    fields.forEach((field, value) -> {
      if (field.analysis == JavaFileSearchField.Analysis.QUALIFIED) {
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().map(Character::toLowerCase).forEach(normalized::appendCodePoint);
        // Replacing ASCII dots with spaces does not change UTF-8 length.
        requireKeywordSize(normalized.toString(), "fields." + field.name());
      }
    });
  }

  static void requireKeywordSize(String value, String name) {
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException(name + " must contain valid Unicode");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_KEYWORD_UTF8_BYTES) {
      throw new IllegalArgumentException(name + " exceeds " + MAX_KEYWORD_UTF8_BYTES + " UTF-8 bytes after analysis");
    }
  }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
