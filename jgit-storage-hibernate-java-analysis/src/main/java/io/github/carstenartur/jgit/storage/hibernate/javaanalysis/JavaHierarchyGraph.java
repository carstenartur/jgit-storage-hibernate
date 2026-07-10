/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds inheritance, implementation and override relations to a project graph. */
final class JavaHierarchyGraph {

  private static final Pattern TYPE_DECLARATION = Pattern.compile(
      "\\b(class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
          + "(?:\\s+extends\\s+([^\\{]+?))?"
          + "(?:\\s+implements\\s+([^\\{]+?))?\\s*\\{");

  private JavaHierarchyGraph() {}

  static List<JavaGraphEdge> extract(JavaProjectAnalysisResult analysis) {
    Map<String, JavaSymbolIndex> typesByName = new HashMap<>();
    Map<String, List<JavaSymbolIndex>> methodsByDeclaringType = new HashMap<>();
    for (JavaSymbolIndex symbol : analysis.symbols()) {
      if (isType(symbol)) {
        typesByName.put(symbol.getSimpleName(), symbol);
        if (symbol.getQualifiedName() != null) {
          typesByName.put(symbol.getQualifiedName(), symbol);
        }
      } else if (symbol.getSymbolKind() == JavaSymbolKind.METHOD) {
        methodsByDeclaringType.computeIfAbsent(symbol.getDeclaringType(), ignored -> new ArrayList<>()).add(symbol);
      }
    }

    List<JavaGraphEdge> edges = new ArrayList<>();
    for (JavaSourceSnapshot source : analysis.project().sources().values()) {
      Matcher matcher = TYPE_DECLARATION.matcher(source.source());
      while (matcher.find()) {
        JavaSymbolIndex child = typesByName.get(matcher.group(2));
        if (child == null) {
          continue;
        }
        addParents(edges, analysis, child, matcher.group(3), JavaGraphEdgeKind.EXTENDS, typesByName);
        addParents(edges, analysis, child, matcher.group(4), JavaGraphEdgeKind.IMPLEMENTS, typesByName);
      }
    }

    for (JavaGraphEdge hierarchy : List.copyOf(edges)) {
      JavaSymbolIndex childType = analysisSymbol(analysis, hierarchy.sourceSemanticKey());
      JavaSymbolIndex parentType = analysisSymbol(analysis, hierarchy.targetSemanticKey());
      if (childType == null || parentType == null) {
        continue;
      }
      for (JavaSymbolIndex childMethod : methodsByDeclaringType.getOrDefault(childType.getQualifiedName(), List.of())) {
        for (JavaSymbolIndex parentMethod : methodsByDeclaringType.getOrDefault(parentType.getQualifiedName(), List.of())) {
          if (sameMethodShape(childMethod, parentMethod)) {
            edges.add(new JavaGraphEdge(
                childMethod.getRepositoryName(), childMethod.getCommitId(), JavaGraphEdgeKind.OVERRIDES,
                childMethod.getStableSemanticKey(), parentMethod.getStableSemanticKey(),
                childMethod.getPath(), childMethod.getStartLine(), childMethod.getBindingStatus()));
          }
        }
      }
    }
    return edges;
  }

  private static void addParents(
      List<JavaGraphEdge> edges,
      JavaProjectAnalysisResult analysis,
      JavaSymbolIndex child,
      String rawParents,
      JavaGraphEdgeKind kind,
      Map<String, JavaSymbolIndex> typesByName) {
    if (rawParents == null || rawParents.isBlank()) {
      return;
    }
    for (String rawParent : rawParents.split(",")) {
      String name = rawParent.trim().replaceAll("<.*>", "");
      JavaSymbolIndex parent = typesByName.get(name);
      String target = parent == null
          ? "TYPE:" + name + ":"
          : parent.getStableSemanticKey();
      edges.add(new JavaGraphEdge(
          child.getRepositoryName(), analysis.project().commitId(), kind,
          child.getStableSemanticKey(), target, child.getPath(), child.getStartLine(),
          parent == null ? BindingStatus.PARTIAL : BindingStatus.FULL));
    }
  }

  private static boolean sameMethodShape(JavaSymbolIndex left, JavaSymbolIndex right) {
    return Objects.equals(left.getSimpleName(), right.getSimpleName())
        && Objects.equals(left.getParameterTypes(), right.getParameterTypes());
  }

  private static boolean isType(JavaSymbolIndex symbol) {
    return switch (symbol.getSymbolKind()) {
      case TYPE, ENUM, RECORD, ANNOTATION_TYPE -> true;
      default -> false;
    };
  }

  private static JavaSymbolIndex analysisSymbol(JavaProjectAnalysisResult analysis, String key) {
    return analysis.symbols().stream()
        .filter(symbol -> Objects.equals(symbol.getStableSemanticKey(), key))
        .findFirst().orElse(null);
  }
}
