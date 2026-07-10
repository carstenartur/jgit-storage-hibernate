/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.Map;
import java.util.Objects;

/** Evidence connecting architecture intent to code, documents or decisions. */
public record ArchitectureEvidence(
    String id,
    String subjectId,
    String kind,
    String repositoryName,
    String commitId,
    String path,
    Integer line,
    String rationale,
    double confidence,
    Map<String, String> attributes) {
  public ArchitectureEvidence {
    Objects.requireNonNull(id, "id"); Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(rationale, "rationale");
    if (confidence < 0.0 || confidence > 1.0) throw new IllegalArgumentException("confidence must be between 0 and 1");
    attributes = Map.copyOf(Objects.requireNonNullElse(attributes, Map.of()));
  }
}
