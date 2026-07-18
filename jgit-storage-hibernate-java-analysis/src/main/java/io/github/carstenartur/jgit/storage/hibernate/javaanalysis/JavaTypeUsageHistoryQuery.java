/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaTypeUsageHistory.UsageSite;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaTypeUsageHistory.Version;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers which code locations use one logical Java type in each analyzed repository version.
 *
 * <p>The query follows the type through the existing {@link SymbolTimeMachine}, so callers may use
 * an old qualified name and still inspect usages after a package move or semantic rename.
 */
public final class JavaTypeUsageHistoryQuery {

  private static final Set<JavaSymbolKind> TYPE_KINDS =
      EnumSet.of(
          JavaSymbolKind.TYPE,
          JavaSymbolKind.ENUM,
          JavaSymbolKind.RECORD,
          JavaSymbolKind.ANNOTATION_TYPE);

  private static final Set<JavaGraphEdgeKind> HIERARCHY_USAGE_RELATIONS =
      EnumSet.of(
          JavaGraphEdgeKind.EXTENDS,
          JavaGraphEdgeKind.IMPLEMENTS,
          JavaGraphEdgeKind.ANNOTATED_WITH);

  /**
   * Find the usage history for a type identity.
   *
   * @param orderedCommits analyses ordered from oldest to newest
   * @param identity stable semantic key, binding key, qualified name or
   *     {@code KIND:qualifiedName}
   * @return usage history, or empty when no matching type timeline exists
   */
  public Optional<JavaTypeUsageHistory> find(
      List<JavaProjectAnalysisResult> orderedCommits, String identity) {
    Objects.requireNonNull(orderedCommits, "orderedCommits");
    String requiredIdentity = requireIdentity(identity);
    if (orderedCommits.isEmpty()) {
      return Optional.empty();
    }

    List<SymbolTimeline> timelines = new SymbolTimeMachine().build(orderedCommits);
    SymbolTimeline timeline =
        timelines.stream()
            .filter(
                candidate ->
                    candidate.entries().stream()
                        .map(SymbolTimelineEntry::symbol)
                        .anyMatch(symbol -> isType(symbol) && matches(symbol, requiredIdentity)))
            .findFirst()
            .orElse(null);
    if (timeline == null) {
      return Optional.empty();
    }

    List<Version> versions = new ArrayList<>();
    for (SymbolTimelineEntry entry : timeline.entries()) {
      JavaProjectAnalysisResult analysis =
          analysisFor(orderedCommits, entry.commitIndex(), entry.commitId());
      versions.add(
          new Version(
              entry.commitIndex(),
              entry.commitId(),
              entry.symbol(),
              usageSites(analysis, entry.symbol())));
    }
    return Optional.of(new JavaTypeUsageHistory(timeline.logicalId(), versions));
  }

  private static List<UsageSite> usageSites(
      JavaProjectAnalysisResult analysis, JavaSymbolIndex targetType) {
    Map<String, JavaSymbolIndex> symbolsByKey = symbolsByKey(analysis.symbols());
    Map<UsageKey, UsageSite> unique = new LinkedHashMap<>();

    for (JavaReferenceIndex reference : analysis.references()) {
      JavaGraphEdgeKind relation = directTypeUsageKind(reference.getReferenceKind());
      if (relation == null || !referenceTargetsType(reference, targetType)) {
        continue;
      }

      JavaSymbolIndex source = enclosingSymbol(analysis.symbols(), reference);
      if (source == null && reference.getSourceSymbolKey() != null) {
        source = symbolsByKey.get(reference.getSourceSymbolKey());
      }
      if (source == null || source.getStableSemanticKey() == null) {
        continue;
      }

      add(
          unique,
          new UsageSite(
              relation,
              source.getStableSemanticKey(),
              source.getQualifiedName(),
              source.getSymbolKind(),
              reference.getPath(),
              reference.getStartLine(),
              reference.getBindingStatus()));
    }

    String targetKey = targetType.getStableSemanticKey();
    if (targetKey != null && !targetKey.isBlank()) {
      JavaSoftwareGraph graph = JavaSoftwareGraph.from(analysis);
      for (JavaGraphEdge edge : graph.incoming(targetKey)) {
        if (!HIERARCHY_USAGE_RELATIONS.contains(edge.kind())) {
          continue;
        }
        JavaSymbolIndex source = graph.symbols().get(edge.sourceSemanticKey());
        add(
            unique,
            new UsageSite(
                edge.kind(),
                edge.sourceSemanticKey(),
                source == null ? null : source.getQualifiedName(),
                source == null ? null : source.getSymbolKind(),
                edge.sourcePath(),
                edge.sourceLine(),
                edge.bindingStatus()));
      }
    }

    return unique.values().stream()
        .sorted(
            Comparator.comparing(
                    UsageSite::path, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(UsageSite::line)
                .thenComparing(site -> site.relation().name())
                .thenComparing(UsageSite::sourceSemanticKey))
        .toList();
  }

  private static void add(Map<UsageKey, UsageSite> unique, UsageSite site) {
    unique.putIfAbsent(
        new UsageKey(site.relation(), site.sourceSemanticKey(), site.path(), site.line()), site);
  }

  private static Map<String, JavaSymbolIndex> symbolsByKey(List<JavaSymbolIndex> symbols) {
    Map<String, JavaSymbolIndex> result = new LinkedHashMap<>();
    for (JavaSymbolIndex symbol : symbols) {
      addKey(result, symbol.getStableSemanticKey(), symbol);
      addKey(result, symbol.getDeclarationBindingKey(), symbol);
      addKey(result, symbol.getRawBindingKey(), symbol);
    }
    return result;
  }

  private static void addKey(
      Map<String, JavaSymbolIndex> symbols, String key, JavaSymbolIndex symbol) {
    if (key != null && !key.isBlank()) {
      symbols.putIfAbsent(key, symbol);
    }
  }

  private static JavaSymbolIndex enclosingSymbol(
      List<JavaSymbolIndex> symbols, JavaReferenceIndex reference) {
    JavaSymbolIndex best = null;
    for (JavaSymbolIndex symbol : symbols) {
      if (!Objects.equals(symbol.getPath(), reference.getPath())) {
        continue;
      }
      int position = reference.getStartPosition();
      int start = symbol.getStartPosition();
      int end = start + symbol.getSourceLength();
      if (position < start || position >= end) {
        continue;
      }
      if (best == null || symbol.getSourceLength() < best.getSourceLength()) {
        best = symbol;
      }
    }
    return best;
  }

  private static boolean referenceTargetsType(
      JavaReferenceIndex reference, JavaSymbolIndex targetType) {
    if (same(reference.getTargetStableSemanticKey(), targetType.getStableSemanticKey())) {
      return true;
    }

    Set<String> targetBindingKeys = new LinkedHashSet<>();
    add(targetBindingKeys, targetType.getRawBindingKey());
    add(targetBindingKeys, targetType.getDeclarationBindingKey());
    add(targetBindingKeys, targetType.getTypeBindingKey());

    if (targetBindingKeys.contains(reference.getRawBindingKey())
        || targetBindingKeys.contains(reference.getDeclarationBindingKey())
        || targetBindingKeys.contains(reference.getTargetTypeBindingKey())) {
      return true;
    }

    if (reference.getBindingStatus() == BindingStatus.FULL) {
      return false;
    }
    return same(reference.getReferenceName(), targetType.getSimpleName())
        || same(reference.getReferenceName(), targetType.getQualifiedName());
  }

  private static void add(Set<String> values, String value) {
    if (value != null && !value.isBlank()) {
      values.add(value);
    }
  }

  private static JavaGraphEdgeKind directTypeUsageKind(JavaReferenceKind kind) {
    return switch (kind) {
      case TYPE_REFERENCE -> JavaGraphEdgeKind.REFERENCES_TYPE;
      case CONSTRUCTOR_INVOCATION -> JavaGraphEdgeKind.CONSTRUCTS;
      case ANNOTATION_USE -> JavaGraphEdgeKind.ANNOTATED_WITH;
      case IMPORT, METHOD_INVOCATION, FIELD_ACCESS -> null;
    };
  }

  private static JavaProjectAnalysisResult analysisFor(
      List<JavaProjectAnalysisResult> orderedCommits, int commitIndex, String commitId) {
    if (commitIndex >= 0 && commitIndex < orderedCommits.size()) {
      JavaProjectAnalysisResult candidate = orderedCommits.get(commitIndex);
      if (commitId.equals(candidate.project().commitId())) {
        return candidate;
      }
    }
    return orderedCommits.stream()
        .filter(candidate -> commitId.equals(candidate.project().commitId()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("missing analysis for commit " + commitId));
  }

  private static boolean isType(JavaSymbolIndex symbol) {
    return TYPE_KINDS.contains(symbol.getSymbolKind());
  }

  private static boolean matches(JavaSymbolIndex symbol, String identity) {
    return same(identity, symbol.getStableSemanticKey())
        || same(identity, symbol.getDeclarationBindingKey())
        || same(identity, symbol.getRawBindingKey())
        || same(identity, symbol.getQualifiedName())
        || same(identity, symbol.getSymbolKind() + ":" + symbol.getQualifiedName());
  }

  private static boolean same(String left, String right) {
    return left != null && left.equals(right);
  }

  private static String requireIdentity(String identity) {
    if (identity == null || identity.isBlank()) {
      throw new IllegalArgumentException("identity must not be blank");
    }
    return identity.trim();
  }

  private record UsageKey(
      JavaGraphEdgeKind relation, String sourceSemanticKey, String path, int line) {}
}
