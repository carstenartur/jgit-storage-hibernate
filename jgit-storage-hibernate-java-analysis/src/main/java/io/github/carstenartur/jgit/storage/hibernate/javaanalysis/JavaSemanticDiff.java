/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compares Java declarations by binding identity and stable semantic identity instead of lines. */
public final class JavaSemanticDiff {

  public List<SemanticChange> compare(
      JavaProjectAnalysisResult before, JavaProjectAnalysisResult after) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");

    List<SemanticChange> changes = new ArrayList<>();
    Map<String, JavaSymbolIndex> afterByIdentity = index(after.symbols());
    Set<JavaSymbolIndex> matchedAfter = new HashSet<>();

    for (JavaSymbolIndex oldSymbol : before.symbols()) {
      Match match = findMatch(oldSymbol, after.symbols(), afterByIdentity, matchedAfter);
      if (match.symbol() == null) {
        changes.add(new SemanticChange(
            SemanticChangeKind.REMOVED, oldSymbol, null, 1.0, "No declaration with matching binding or semantic identity"));
        continue;
      }
      JavaSymbolIndex newSymbol = match.symbol();
      matchedAfter.add(newSymbol);
      compareMatched(oldSymbol, newSymbol, match.confidence(), match.evidence(), changes);
    }

    for (JavaSymbolIndex newSymbol : after.symbols()) {
      if (!matchedAfter.contains(newSymbol)) {
        changes.add(new SemanticChange(
            SemanticChangeKind.ADDED, null, newSymbol, 1.0, "No declaration with matching binding or semantic identity"));
      }
    }
    return List.copyOf(changes);
  }

  private static void compareMatched(
      JavaSymbolIndex before,
      JavaSymbolIndex after,
      double confidence,
      String evidence,
      List<SemanticChange> changes) {
    if (!Objects.equals(before.getPath(), after.getPath())) {
      changes.add(new SemanticChange(SemanticChangeKind.MOVED, before, after, confidence, evidence));
    }
    if (!Objects.equals(before.getSimpleName(), after.getSimpleName())) {
      changes.add(new SemanticChange(SemanticChangeKind.RENAMED, before, after, confidence, evidence));
    }
    if (!Objects.equals(before.getSignature(), after.getSignature())) {
      changes.add(new SemanticChange(SemanticChangeKind.SIGNATURE_CHANGED, before, after, confidence, evidence));
    }
    if (!Objects.equals(before.getModifiers(), after.getModifiers())) {
      changes.add(new SemanticChange(SemanticChangeKind.VISIBILITY_CHANGED, before, after, confidence, evidence));
    }
    if (!Objects.equals(before.getAnnotations(), after.getAnnotations())) {
      changes.add(new SemanticChange(SemanticChangeKind.ANNOTATIONS_CHANGED, before, after, confidence, evidence));
    }
    if (before.getBindingStatus() != after.getBindingStatus()) {
      changes.add(new SemanticChange(SemanticChangeKind.BINDING_QUALITY_CHANGED, before, after, confidence, evidence));
    }
  }

  private static Match findMatch(
      JavaSymbolIndex oldSymbol,
      List<JavaSymbolIndex> candidates,
      Map<String, JavaSymbolIndex> indexed,
      Set<JavaSymbolIndex> matched) {
    for (String key : identityKeys(oldSymbol)) {
      JavaSymbolIndex candidate = indexed.get(key);
      if (candidate != null && !matched.contains(candidate)) {
        return new Match(candidate, key.startsWith("binding:") ? 1.0 : 0.95, "Matched by " + key.substring(0, key.indexOf(':')));
      }
    }

    JavaSymbolIndex best = null;
    double bestScore = 0.0;
    for (JavaSymbolIndex candidate : candidates) {
      if (matched.contains(candidate) || candidate.getSymbolKind() != oldSymbol.getSymbolKind()) {
        continue;
      }
      double score = heuristicScore(oldSymbol, candidate);
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return bestScore >= 0.65
        ? new Match(best, bestScore, "Matched heuristically by declaration shape")
        : new Match(null, 0.0, "No match");
  }

  private static double heuristicScore(JavaSymbolIndex left, JavaSymbolIndex right) {
    double score = 0.0;
    if (Objects.equals(left.getDeclaringType(), right.getDeclaringType())) {
      score += 0.25;
    }
    if (Objects.equals(left.getSimpleName(), right.getSimpleName())) {
      score += 0.30;
    }
    if (Objects.equals(left.getParameterTypes(), right.getParameterTypes())) {
      score += 0.25;
    }
    if (Objects.equals(left.getReturnType(), right.getReturnType())) {
      score += 0.10;
    }
    if (Objects.equals(left.getAnnotations(), right.getAnnotations())) {
      score += 0.05;
    }
    if (Objects.equals(fileName(left.getPath()), fileName(right.getPath()))) {
      score += 0.05;
    }
    return score;
  }

  private static Map<String, JavaSymbolIndex> index(List<JavaSymbolIndex> symbols) {
    Map<String, JavaSymbolIndex> result = new HashMap<>();
    for (JavaSymbolIndex symbol : symbols) {
      for (String key : identityKeys(symbol)) {
        result.putIfAbsent(key, symbol);
      }
    }
    return result;
  }

  private static List<String> identityKeys(JavaSymbolIndex symbol) {
    List<String> keys = new ArrayList<>();
    add(keys, "binding:", symbol.getDeclarationBindingKey());
    add(keys, "raw:", symbol.getRawBindingKey());
    add(keys, "semantic:", symbol.getStableSemanticKey());
    add(keys, "qualified:", symbol.getSymbolKind() + ":" + symbol.getQualifiedName());
    return keys;
  }

  private static void add(List<String> keys, String prefix, String value) {
    if (value != null && !value.isBlank()) {
      keys.add(prefix + value);
    }
  }

  private static String fileName(String path) {
    int slash = path == null ? -1 : path.lastIndexOf('/');
    return path == null ? null : path.substring(slash + 1);
  }

  private record Match(JavaSymbolIndex symbol, double confidence, String evidence) {}
}
