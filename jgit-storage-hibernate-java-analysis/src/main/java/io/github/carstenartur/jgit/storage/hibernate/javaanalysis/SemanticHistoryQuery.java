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

  public long unresolvedReferenceCount() {
    return result.references().stream()
        .filter(reference -> reference.getBindingStatus() == BindingStatus.PARTIAL
            || reference.getBindingStatus() == BindingStatus.FAILED)
        .count();
  }
}
