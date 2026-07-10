/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Added and removed semantic graph relations between two commits. */
public record JavaGraphDelta(Set<JavaGraphEdge> added, Set<JavaGraphEdge> removed) {

  public JavaGraphDelta {
    added = Set.copyOf(Objects.requireNonNull(added, "added"));
    removed = Set.copyOf(Objects.requireNonNull(removed, "removed"));
  }

  public static JavaGraphDelta between(JavaSoftwareGraph before, JavaSoftwareGraph after) {
    Set<EdgeIdentity> oldEdges = identities(before.edges());
    Set<EdgeIdentity> newEdges = identities(after.edges());
    Set<JavaGraphEdge> added = new LinkedHashSet<>();
    Set<JavaGraphEdge> removed = new LinkedHashSet<>();
    for (JavaGraphEdge edge : after.edges()) {
      if (!oldEdges.contains(EdgeIdentity.of(edge))) {
        added.add(edge);
      }
    }
    for (JavaGraphEdge edge : before.edges()) {
      if (!newEdges.contains(EdgeIdentity.of(edge))) {
        removed.add(edge);
      }
    }
    return new JavaGraphDelta(added, removed);
  }

  private static Set<EdgeIdentity> identities(Iterable<JavaGraphEdge> edges) {
    Set<EdgeIdentity> result = new LinkedHashSet<>();
    for (JavaGraphEdge edge : edges) {
      result.add(EdgeIdentity.of(edge));
    }
    return result;
  }

  private record EdgeIdentity(JavaGraphEdgeKind kind, String source, String target) {
    static EdgeIdentity of(JavaGraphEdge edge) {
      return new EdgeIdentity(edge.kind(), edge.sourceSemanticKey(), edge.targetSemanticKey());
    }
  }
}
