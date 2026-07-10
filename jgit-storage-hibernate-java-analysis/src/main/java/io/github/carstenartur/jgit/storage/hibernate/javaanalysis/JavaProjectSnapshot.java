/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Map;
import java.util.Objects;

/** Immutable Java project sources at one Git commit. */
public record JavaProjectSnapshot(
    String repositoryName, String commitId, Map<String, JavaSourceSnapshot> sources) {

  public JavaProjectSnapshot {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commitId, "commitId");
    sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
    sources.forEach(
        (path, source) -> {
          if (!path.equals(source.path())) {
            throw new IllegalArgumentException("Source map key must equal snapshot path: " + path);
          }
          if (!repositoryName.equals(source.repositoryName()) || !commitId.equals(source.commitId())) {
            throw new IllegalArgumentException("All sources must belong to the project repository and commit");
          }
        });
  }
}
