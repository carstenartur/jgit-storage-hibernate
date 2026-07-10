/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdge;
import java.util.List;
import java.util.Objects;

/** One explainable architecture drift finding. */
public record ArchitectureDriftFinding(
    String id,
    ArchitectureDriftKind kind,
    String ruleId,
    String sourceElementId,
    String targetElementId,
    JavaGraphEdge observedEdge,
    String message,
    List<String> evidenceIds) {
  public ArchitectureDriftFinding {
    Objects.requireNonNull(id, "id"); Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(message, "message");
    evidenceIds = List.copyOf(Objects.requireNonNullElse(evidenceIds, List.of()));
  }
}
