/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(dslId, "dslId");
    Objects.requireNonNull(dslVersion, "dslVersion");
    elements = List.copyOf(elements);
    relations = List.copyOf(relations);
    rules = List.copyOf(rules);
    evidence = List.copyOf(evidence);
    Map<String, ArchitectureElement> elementIds = new LinkedHashMap<>();
    for (ArchitectureElement element : elements) {
      if (elementIds.put(element.id(), element) != null) {
        throw new IllegalArgumentException("Duplicate element ID in snapshot: " + element.id());
      }
    }
    for (ArchitectureRelation relation : relations) {
      if (!elementIds.containsKey(relation.sourceId()) || !elementIds.containsKey(relation.targetId())) {
        throw new IllegalArgumentException("Unknown element IDs in relation " + relation.id()
            + ": source=" + relation.sourceId() + ", target=" + relation.targetId());
      }
    }
    for (ArchitectureRule rule : rules) {
      if (!elementIds.containsKey(rule.sourceElementId()) || !elementIds.containsKey(rule.targetElementId())) {
        throw new IllegalArgumentException("Unknown element IDs in rule " + rule.id()
            + ": source=" + rule.sourceElementId() + ", target=" + rule.targetElementId());
      }
    }
  }

  public ArchitectureElement element(String id) {
    return elements.stream().filter(element -> element.id().equals(id)).findFirst().orElse(null);
  }
}
