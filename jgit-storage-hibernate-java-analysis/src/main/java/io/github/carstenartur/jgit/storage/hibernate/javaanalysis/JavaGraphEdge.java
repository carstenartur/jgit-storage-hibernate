/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Objects;

/** Immutable semantic relation at one repository commit. */
public record JavaGraphEdge(
    String repositoryName,
    String commitId,
    JavaGraphEdgeKind kind,
    String sourceSemanticKey,
    String targetSemanticKey,
    String sourcePath,
    int sourceLine,
    BindingStatus bindingStatus) {

  public JavaGraphEdge {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(sourceSemanticKey, "sourceSemanticKey");
    Objects.requireNonNull(targetSemanticKey, "targetSemanticKey");
    Objects.requireNonNull(bindingStatus, "bindingStatus");
  }
}
