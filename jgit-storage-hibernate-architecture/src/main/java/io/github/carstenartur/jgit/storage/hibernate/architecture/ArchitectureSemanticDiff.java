/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Compares versioned architecture snapshots through stable element, relation, rule and evidence IDs. */
public final class ArchitectureSemanticDiff {

  public List<ArchitectureChange> compare(ArchitectureSnapshot before, ArchitectureSnapshot after) {
    List<ArchitectureChange> changes = new ArrayList<>();
    compare(
        index(before.elements(), ArchitectureElement::id), index(after.elements(), ArchitectureElement::id),
        ArchitectureChangeKind.ELEMENT_ADDED, ArchitectureChangeKind.ELEMENT_REMOVED,
        ArchitectureChangeKind.ELEMENT_CHANGED, changes);
    compare(
        index(before.relations(), ArchitectureRelation::id), index(after.relations(), ArchitectureRelation::id),
        ArchitectureChangeKind.RELATION_ADDED, ArchitectureChangeKind.RELATION_REMOVED,
        ArchitectureChangeKind.RELATION_CHANGED, changes);
    compare(
        index(before.rules(), ArchitectureRule::id), index(after.rules(), ArchitectureRule::id),
        ArchitectureChangeKind.RULE_ADDED, ArchitectureChangeKind.RULE_REMOVED,
        ArchitectureChangeKind.RULE_CHANGED, changes);
    compare(
        index(before.evidence(), ArchitectureEvidence::id), index(after.evidence(), ArchitectureEvidence::id),
        ArchitectureChangeKind.EVIDENCE_ADDED, ArchitectureChangeKind.EVIDENCE_REMOVED,
        ArchitectureChangeKind.EVIDENCE_CHANGED, changes);
    return List.copyOf(changes);
  }

  private static <T> void compare(
      Map<String, T> before,
      Map<String, T> after,
      ArchitectureChangeKind added,
      ArchitectureChangeKind removed,
      ArchitectureChangeKind changed,
      List<ArchitectureChange> output) {
    before.forEach((id, oldValue) -> {
      T newValue = after.get(id);
      if (newValue == null) output.add(new ArchitectureChange(removed, id, oldValue, null));
      else if (!Objects.equals(oldValue, newValue)) output.add(new ArchitectureChange(changed, id, oldValue, newValue));
    });
    after.forEach((id, newValue) -> {
      if (!before.containsKey(id)) output.add(new ArchitectureChange(added, id, null, newValue));
    });
  }

  private static <T> Map<String, T> index(List<T> values, Function<T, String> idExtractor) {
    Map<String, T> result = new LinkedHashMap<>();
    values.forEach(value -> result.put(idExtractor.apply(value), value));
    return result;
  }
}
