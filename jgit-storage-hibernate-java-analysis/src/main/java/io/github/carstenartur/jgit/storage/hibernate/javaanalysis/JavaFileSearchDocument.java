/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Map;

/**
 * One historical file document. Identity is (repositoryName, documentId); callers may use blobId
 * to deduplicate identical content. The first supplied identity retains all its provenance and fields.
 * Qualified-name facets are single whole strings, including any embedded newlines.
 */
public record JavaFileSearchDocument(
    String repositoryName, String documentId, String commitId, String blobId, String path,
    String module, String fileType, Map<JavaFileSearchField, String> fields) {

  public JavaFileSearchDocument {
    requireText(repositoryName, "repositoryName");
    requireText(documentId, "documentId");
    requireText(commitId, "commitId");
    requireText(blobId, "blobId");
    requireText(path, "path");
    module = module == null ? "" : module;
    fileType = fileType == null ? "" : fileType;
    fields = Map.copyOf(fields);
  }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
