/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaTypeUsageHistory.UsageSite;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaTypeUsageHistory.Version;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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

  private static final Set<JavaGraphEdgeKind> TYPE_USAGE_RELATIONS =
      EnumSet.of(
          JavaGraphEdgeKind.CONSTRUCTS,
          JavaGraphEdgeKind.REFERENCES_TYPE,
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
      JavaSoftwareGraph graph = JavaSoftwareGraph.from(analysis);
      versions.add(
          new Version(
              entry.commitIndex(),
              entry.commitId(),
              entry.symbol(),
              usageSites(graph, entry.symbol())));
    }
    return Optional.of(new JavaTypeUsageHistory(timeline.logicalId(), versions));
  }

  private static List<UsageSite> usageSites(
      JavaSoftwareGraph graph, JavaSymbolIndex targetType) {
    String targetKey = targetType.getStableSemanticKey();
    if (targetKey == null || targetKey.isBlank()) {
      return List.of();
    }

    Map<UsageKey, UsageSite> unique = new LinkedHashMap<>();
    for (JavaGraphEdge edge : graph.incoming(targetKey)) {
      if (!TYPE_USAGE_RELATIONS.contains(edge.kind())) {
        continue;
      }
      JavaSymbolIndex source = graph.symbols().get(edge.sourceSemanticKey());
      UsageSite site =
          new UsageSite(
              edge.kind(),
              edge.sourceSemanticKey(),
              source == null ? null : source.getQualifiedName(),
              source == null ? null : source.getSymbolKind(),
              edge.sourcePath(),
              edge.sourceLine(),
              edge.bindingStatus());
      unique.putIfAbsent(
          new UsageKey(edge.kind(), edge.sourceSemanticKey(), edge.sourcePath(), edge.sourceLine()),
          site);
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
    return identity.equals(symbol.getStableSemanticKey())
        || identity.equals(symbol.getDeclarationBindingKey())
        || identity.equals(symbol.getRawBindingKey())
        || identity.equals(symbol.getQualifiedName())
        || identity.equals(symbol.getSymbolKind() + ":" + symbol.getQualifiedName());
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
