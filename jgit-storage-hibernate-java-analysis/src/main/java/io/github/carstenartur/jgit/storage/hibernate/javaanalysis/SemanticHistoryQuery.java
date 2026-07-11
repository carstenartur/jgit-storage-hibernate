/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Small public query facade independent of JDT and Hibernate Search APIs. */
public final class SemanticHistoryQuery {

  public record SemanticHistoryQueryPage<T>(List<T> items, int offset, int pageSize, int totalCount) {
    public SemanticHistoryQueryPage {
      items = List.copyOf(items);
    }
  }

  private final JavaProjectAnalysisResult result;

  public SemanticHistoryQuery(JavaProjectAnalysisResult result) {
    this.result = Objects.requireNonNull(result, "result");
  }

  public List<JavaSymbolIndex> symbols(Predicate<JavaSymbolIndex> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    return result.symbols().stream().filter(predicate).toList();
  }

  public List<JavaSymbolIndex> symbolsNamed(String simpleName) {
    Objects.requireNonNull(simpleName, "simpleName");
    return symbols(symbol -> simpleName.equals(symbol.getSimpleName()));
  }

  public List<JavaSymbolIndex> methodsReturning(String qualifiedTypeName) {
    Objects.requireNonNull(qualifiedTypeName, "qualifiedTypeName");
    return symbols(
        symbol -> symbol.getSymbolKind() == JavaSymbolKind.METHOD
            && qualifiedTypeName.equals(symbol.getReturnType()));
  }

  public List<JavaReferenceIndex> referencesToSemanticKey(String stableSemanticKey) {
    Objects.requireNonNull(stableSemanticKey, "stableSemanticKey");
    return result.references().stream()
        .filter(reference -> stableSemanticKey.equals(reference.getTargetStableSemanticKey()))
        .toList();
  }

  public List<JavaReferenceIndex> methodInvocationsNamed(String methodName) {
    Objects.requireNonNull(methodName, "methodName");
    return result.references().stream()
        .filter(reference -> reference.getReferenceKind() == JavaReferenceKind.METHOD_INVOCATION)
        .filter(reference -> methodName.equals(reference.getReferenceName()))
        .toList();
  }

  public List<JavaSymbolIndex> symbolsOfKind(JavaSymbolKind kind) {
    Objects.requireNonNull(kind, "kind");
    return symbols(symbol -> symbol.getSymbolKind() == kind);
  }

  public List<JavaSymbolIndex> symbolsWithBinding(BindingStatus minStatus) {
    Objects.requireNonNull(minStatus, "minStatus");
    int threshold = bindingRank(minStatus);
    return result.symbols().stream()
        .filter(symbol -> bindingRank(symbol.getBindingStatus()) >= threshold)
        .toList();
  }

  public List<SemanticChange> changesTo(JavaProjectAnalysisResult after) {
    Objects.requireNonNull(after, "after");
    return new JavaSemanticDiff().compare(result, after);
  }

  public List<SemanticChange> changesOfKind(JavaProjectAnalysisResult after, SemanticChangeKind kind) {
    Objects.requireNonNull(kind, "kind");
    return changesTo(after).stream().filter(change -> change.kind() == kind).toList();
  }

  public List<JavaSymbolIndex> movedSymbols(JavaProjectAnalysisResult after) {
    return changesOfKind(after, SemanticChangeKind.MOVED).stream()
        .map(change -> change.after() == null ? change.before() : change.after())
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  public List<JavaReferenceIndex> referencesOnLine(String path, int line) {
    Objects.requireNonNull(path, "path");
    return result.references().stream()
        .filter(reference -> path.equals(reference.getPath()))
        .filter(reference -> reference.getStartLine() <= line && reference.getEndLine() >= line)
        .toList();
  }

  public SemanticHistoryQueryPage<JavaSymbolIndex> symbolsPage(
      Predicate<JavaSymbolIndex> predicate, int offset, int pageSize) {
    Objects.requireNonNull(predicate, "predicate");
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (pageSize < 0) {
      throw new IllegalArgumentException("pageSize must not be negative");
    }
    List<JavaSymbolIndex> filtered = symbols(predicate);
    int from = Math.min(offset, filtered.size());
    int to = Math.min(from + pageSize, filtered.size());
    return new SemanticHistoryQueryPage<>(filtered.subList(from, to), offset, pageSize, filtered.size());
  }

  public long unresolvedReferenceCount() {
    return result.references().stream()
        .filter(reference -> reference.getBindingStatus() == BindingStatus.PARTIAL
            || reference.getBindingStatus() == BindingStatus.FAILED)
        .count();
  }

  private static int bindingRank(BindingStatus status) {
    return switch (status) {
      case NONE -> 0;
      case PARTIAL -> 1;
      case RECOVERED -> 2;
      case FULL -> 3;
      case FAILED -> -1;
    };
  }
}
