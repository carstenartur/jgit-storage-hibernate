/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adds inheritance, implementation and override relations to a project graph. */
final class JavaHierarchyGraph {

  private JavaHierarchyGraph() {}

  static List<JavaGraphEdge> extract(JavaProjectAnalysisResult analysis) {
    Map<String, JavaSymbolIndex> typesByName = new HashMap<>();
    Map<String, JavaSymbolIndex> typesByKey = new HashMap<>();
    Map<String, JavaSymbolIndex> typesByPosition = new HashMap<>();
    Map<String, List<JavaSymbolIndex>> methodsByDeclaringType = new HashMap<>();
    for (JavaSymbolIndex symbol : analysis.symbols()) {
      if (isType(symbol)) {
        typesByName.put(symbol.getQualifiedName(), symbol);
        typesByKey.put(symbol.getStableSemanticKey(), symbol);
        typesByPosition.put(symbol.getPath() + ":" + symbol.getStartPosition(), symbol);
      } else if (symbol.getSymbolKind() == JavaSymbolKind.METHOD) {
        methodsByDeclaringType.computeIfAbsent(symbol.getDeclaringType(), ignored -> new ArrayList<>()).add(symbol);
      }
    }

    List<JavaGraphEdge> edges = new ArrayList<>();
    for (JavaSourceSnapshot source : analysis.project().sources().values()) {
      JavaAnalysisResult file = analysis.files().get(source.path());
      String level = file == null ? "21" : file.analysisRun().getSourceLevel();
      var configuration = new JavaAnalysisConfiguration(level, BindingMode.DISABLED,
          List.of(), List.of(), List.of(), List.of(), false, null);
      var structure = new JavaSourceStructureExtractor().extract(source, configuration);
      for (var declaration : structure.types()) {
        JavaSymbolIndex child = typesByPosition.get(source.path() + ":" + declaration.startPosition());
        if (child == null) {
          continue;
        }
        addParents(edges, analysis, child, declaration.extendedTypes(), JavaGraphEdgeKind.EXTENDS,
            typesByName, typesByKey, structure.imports());
        addParents(edges, analysis, child, declaration.implementedTypes(), JavaGraphEdgeKind.IMPLEMENTS,
            typesByName, typesByKey, structure.imports());
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
          if (sameMethodShape(childMethod, parentMethod)
              && canOverride(childMethod) && canOverride(parentMethod)) {
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
      List<JavaGraphEdge> edges, JavaProjectAnalysisResult analysis, JavaSymbolIndex child,
      List<JavaSourceStructure.ParentType> parents, JavaGraphEdgeKind kind,
      Map<String, JavaSymbolIndex> typesByName, Map<String, JavaSymbolIndex> typesByKey,
      List<JavaSourceStructure.ImportDeclaration> imports) {
    for (var syntax : parents) {
      var reference = analysis.references().stream()
          .filter(ref -> ref.getReferenceKind() == JavaReferenceKind.TYPE_REFERENCE)
          .filter(ref -> Objects.equals(ref.getPath(), child.getPath()))
          .filter(ref -> ref.getStartPosition() == syntax.startPosition())
          .findFirst().orElse(null);
      String resolvedKey = reference == null ? null : reference.getTargetStableSemanticKey();
      boolean resolved = reference != null && reference.getBindingStatus() == BindingStatus.FULL
          && resolvedKey != null;
      String syntaxName = resolveSyntaxName(child, syntax.typeName(), typesByName, imports);
      JavaSymbolIndex parent = resolved ? typesByKey.get(resolvedKey) : typesByName.get(syntaxName);
      String target = parent != null ? parent.getStableSemanticKey()
          : resolved ? resolvedKey : "TYPE:" + syntaxName + ":";
      edges.add(new JavaGraphEdge(
          child.getRepositoryName(), analysis.project().commitId(), kind,
          child.getStableSemanticKey(), target, child.getPath(), child.getStartLine(),
          resolved ? BindingStatus.FULL : BindingStatus.PARTIAL));
    }
  }

  private static String resolveSyntaxName(JavaSymbolIndex child,
      String name, Map<String, JavaSymbolIndex> typesByName, List<JavaSourceStructure.ImportDeclaration> imports) {
    // Search every enclosing declaration before considering package members.
    String scope = child.getDeclaringType();
    while (scope != null) {
      String scopedName = scope + "." + name;
      if (typesByName.containsKey(scopedName)) {
        return scopedName;
      }
      JavaSymbolIndex enclosing = typesByName.get(scope);
      scope = enclosing == null ? null : enclosing.getDeclaringType();
    }
    String firstName = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
    for (var declaration : imports) {
      if (!declaration.onDemand() && declaration.name().endsWith("." + firstName)) {
        if (declaration.isStatic() && !typesByName.containsKey(declaration.name())) {
          // A static field/method import does not introduce a type name.
          continue;
        }
        // Preserve an explicit imported name even if its dependency is missing.
        return declaration.name() + name.substring(firstName.length());
      }
    }
    if (typesByName.containsKey(name)) {
      return name;
    }
    String packageName = child.getPackageName();
    String qualified = packageName == null || packageName.isBlank() ? name : packageName + "." + name;
    return typesByName.containsKey(qualified) ? qualified : name;
  }

  private static boolean sameMethodShape(JavaSymbolIndex left, JavaSymbolIndex right) {
    return Objects.equals(left.getSimpleName(), right.getSimpleName())
        && Objects.equals(left.getParameterTypes(), right.getParameterTypes());
  }

  private static boolean canOverride(JavaSymbolIndex method) {
    String mods = method.getModifiers();
    if (mods == null) {
      return true;
    }
    for (String token : mods.split("\\s+")) {
      if ("static".equals(token) || "private".equals(token)) {
        return false;
      }
    }
    return true;
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
