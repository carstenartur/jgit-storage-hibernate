/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.Map;
import java.util.Objects;

/** Stable directed relation between architecture elements. */
public record ArchitectureRelation(String id, String kind, String sourceId, String targetId, Map<String, String> attributes) {
  public ArchitectureRelation {
    Objects.requireNonNull(id, "id"); Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(sourceId, "sourceId"); Objects.requireNonNull(targetId, "targetId");
    attributes = Map.copyOf(Objects.requireNonNullElse(attributes, Map.of()));
  }
}
