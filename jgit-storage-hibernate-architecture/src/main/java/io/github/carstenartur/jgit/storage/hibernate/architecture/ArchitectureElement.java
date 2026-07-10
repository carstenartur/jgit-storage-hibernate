/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.Map;
import java.util.Objects;

/** Stable architecture element declared by a versioned DSL. */
public record ArchitectureElement(String id, String kind, String name, Map<String, String> attributes) {
  public ArchitectureElement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    attributes = Map.copyOf(Objects.requireNonNullElse(attributes, Map.of()));
  }
}
