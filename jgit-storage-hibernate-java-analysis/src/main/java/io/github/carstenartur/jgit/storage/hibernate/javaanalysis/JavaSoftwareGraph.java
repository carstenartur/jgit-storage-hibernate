/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable software graph derived from one analyzed commit. */
public final class JavaSoftwareGraph {

  private final JavaProjectAnalysisResult analysis;
  private final Map<String, JavaSymbolIndex> symbols;
  private final List<JavaGraphEdge> edges;

  private JavaSoftwareGraph(
      JavaProjectAnalysisResult analysis,
      Map<String, JavaSymbolIndex> symbols,
      List<JavaGraphEdge> edges) {
    this.analysis = analysis;
    this.symbols = Map.copyOf(symbols);
    this.edges = List.copyOf(edges);
  }

  public static JavaSoftwareGraph from(JavaProjectAnalysisResult analysis) {
    Objects.requireNonNull(analysis, "analysis");
    Map<String, JavaSymbolIndex> symbols = new HashMap<>();
    for (JavaSymbolIndex symbol : analysis.symbols()) {
      if (symbol.getStableSemanticKey() != null) {
        symbols.put(symbol.getStableSemanticKey(), symbol);
      }
    }
    List<JavaGraphEdge> edges = new ArrayList<>();
    for (JavaReferenceIndex reference : analysis.references()) {
      String source = enclosingSymbolKey(analysis.symbols(), reference);
      if (source == null) {
        source = reference.getSourceSymbolKey();
      }
      String target = reference.getTargetStableSemanticKey();
      JavaGraphEdgeKind kind = edgeKind(reference.getReferenceKind());
      if (source == null || target == null || kind == null) {
        continue;
      }
      edges.add(new JavaGraphEdge(
          reference.getRepositoryName(), reference.getCommitId(), kind, source, target,
          reference.getPath(), reference.getStartLine(), reference.getBindingStatus()));
    }
    edges.addAll(JavaHierarchyGraph.extract(analysis));
    return new JavaSoftwareGraph(analysis, symbols, edges);
  }

  public JavaProjectAnalysisResult analysis() { return analysis; }
  public Map<String, JavaSymbolIndex> symbols() { return symbols; }
  public List<JavaGraphEdge> edges() { return edges; }

  public List<JavaGraphEdge> outgoing(String semanticKey) {
    return edges.stream().filter(edge -> edge.sourceSemanticKey().equals(semanticKey)).toList();
  }

  public List<JavaGraphEdge> incoming(String semanticKey) {
    return edges.stream().filter(edge -> edge.targetSemanticKey().equals(semanticKey)).toList();
  }

  public Set<String> transitiveImpact(String semanticKey, int maxDepth) {
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth must not be negative");
    }
    Set<String> impacted = new HashSet<>();
    ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
    queue.add(new NodeDepth(semanticKey, 0));
    while (!queue.isEmpty()) {
      NodeDepth current = queue.removeFirst();
      if (current.depth() >= maxDepth) {
        continue;
      }
      for (JavaGraphEdge edge : incoming(current.key())) {
        if (impacted.add(edge.sourceSemanticKey())) {
          queue.addLast(new NodeDepth(edge.sourceSemanticKey(), current.depth() + 1));
        }
      }
    }
    impacted.remove(semanticKey);
    return Set.copyOf(impacted);
  }

  private static String enclosingSymbolKey(
      List<JavaSymbolIndex> symbols, JavaReferenceIndex reference) {
    JavaSymbolIndex best = null;
    for (JavaSymbolIndex symbol : symbols) {
      if (!Objects.equals(symbol.getPath(), reference.getPath())) {
        continue;
      }
      int referencePosition = reference.getStartPosition();
      int symbolStart = symbol.getStartPosition();
      int symbolEnd = symbolStart + symbol.getSourceLength();
      if (referencePosition < symbolStart || referencePosition >= symbolEnd) {
        continue;
      }
      if (best == null || symbol.getSourceLength() < best.getSourceLength()) {
        best = symbol;
      }
    }
    return best == null ? null : best.getStableSemanticKey();
  }

  private static JavaGraphEdgeKind edgeKind(JavaReferenceKind kind) {
    return switch (kind) {
      case METHOD_INVOCATION -> JavaGraphEdgeKind.CALLS;
      case CONSTRUCTOR_INVOCATION -> JavaGraphEdgeKind.CONSTRUCTS;
      case FIELD_ACCESS -> JavaGraphEdgeKind.READS_FIELD;
      case TYPE_REFERENCE, IMPORT -> JavaGraphEdgeKind.REFERENCES_TYPE;
      case ANNOTATION_USE -> JavaGraphEdgeKind.ANNOTATED_WITH;
    };
  }

  private record NodeDepth(String key, int depth) {}
}
