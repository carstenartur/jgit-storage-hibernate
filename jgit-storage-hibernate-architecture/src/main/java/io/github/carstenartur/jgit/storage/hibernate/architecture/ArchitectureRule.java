/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdgeKind;
import java.util.Objects;

/** Versioned rule constraining code graph relations between architecture elements. */
public record ArchitectureRule(
    String id,
    ArchitectureRuleEffect effect,
    JavaGraphEdgeKind edgeKind,
    String sourceElementId,
    String targetElementId,
    String rationale,
    String evidenceId) {
  public ArchitectureRule {
    Objects.requireNonNull(id, "id"); Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(edgeKind, "edgeKind"); Objects.requireNonNull(sourceElementId, "sourceElementId");
    Objects.requireNonNull(targetElementId, "targetElementId");
    rationale = Objects.requireNonNullElse(rationale, "");
  }
}
