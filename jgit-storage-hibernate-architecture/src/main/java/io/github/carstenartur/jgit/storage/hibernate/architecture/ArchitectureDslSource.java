/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.Objects;

/** Versioned DSL input with Git provenance. */
public record ArchitectureDslSource(
    String repositoryName,
    String commitId,
    String path,
    String content) {
  public ArchitectureDslSource {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
  }
}
