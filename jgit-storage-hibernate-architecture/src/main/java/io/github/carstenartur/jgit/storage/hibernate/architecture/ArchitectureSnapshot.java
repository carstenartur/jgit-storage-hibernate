/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Parsed architecture state at one repository commit. */
public record ArchitectureSnapshot(
    String repositoryName,
    String commitId,
    String dslId,
    String dslVersion,
    List<ArchitectureElement> elements,
    List<ArchitectureRelation> relations,
    List<ArchitectureRule> rules,
    List<ArchitectureEvidence> evidence) {
  public ArchitectureSnapshot {
    Objects.requireNonNull(repositoryName, "repositoryName"); Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(dslId, "dslId"); Objects.requireNonNull(dslVersion, "dslVersion");
    elements = List.copyOf(elements); relations = List.copyOf(relations); rules = List.copyOf(rules); evidence = List.copyOf(evidence);
    Map<String, ArchitectureElement> ids = elements.stream().collect(Collectors.toMap(ArchitectureElement::id, Function.identity()));
    for (ArchitectureRelation relation : relations) {
      if (!ids.containsKey(relation.sourceId()) || !ids.containsKey(relation.targetId()))
        throw new IllegalArgumentException("Unknown relation endpoint: " + relation.id());
    }
  }

  public ArchitectureElement element(String id) {
    return elements.stream().filter(element -> element.id().equals(id)).findFirst().orElse(null);
  }
}
