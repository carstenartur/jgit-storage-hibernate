/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Mapping of semantic code symbol keys to architecture element IDs. */
public record ArchitectureCodeMapping(
    Map<String, String> elementBySemanticKey,
    Map<String, List<String>> ambiguousElementIds,
    List<String> unmappedSemanticKeys) {
  public ArchitectureCodeMapping {
    elementBySemanticKey = Map.copyOf(Objects.requireNonNull(elementBySemanticKey, "elementBySemanticKey"));
    ambiguousElementIds = Map.copyOf(Objects.requireNonNull(ambiguousElementIds, "ambiguousElementIds"));
    unmappedSemanticKeys = List.copyOf(Objects.requireNonNull(unmappedSemanticKeys, "unmappedSemanticKeys"));
  }

  public String elementFor(String semanticKey) { return elementBySemanticKey.get(semanticKey); }
}
