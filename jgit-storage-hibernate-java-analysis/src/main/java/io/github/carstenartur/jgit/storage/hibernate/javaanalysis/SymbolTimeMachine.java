/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds durable logical symbol histories from ordered analyzed commits. */
public final class SymbolTimeMachine {

  public List<SymbolTimeline> build(List<JavaProjectAnalysisResult> orderedCommits) {
    Objects.requireNonNull(orderedCommits, "orderedCommits");
    if (orderedCommits.isEmpty()) {
      return List.of();
    }

    Map<String, List<SymbolTimelineEntry>> tracks = new LinkedHashMap<>();
    Map<String, String> currentTrackByIdentity = new LinkedHashMap<>();
    JavaProjectAnalysisResult first = orderedCommits.getFirst();
    for (JavaSymbolIndex symbol : first.symbols()) {
      String logicalId = logicalId(symbol);
      tracks.computeIfAbsent(logicalId, ignored -> new ArrayList<>())
          .add(entry(0, first, symbol, List.of()));
      addIdentities(currentTrackByIdentity, symbol, logicalId);
    }

    JavaSemanticDiff differ = new JavaSemanticDiff();
    for (int index = 1; index < orderedCommits.size(); index++) {
      JavaProjectAnalysisResult previous = orderedCommits.get(index - 1);
      JavaProjectAnalysisResult current = orderedCommits.get(index);
      List<SemanticChange> changes = differ.compare(previous, current);
      Map<JavaSymbolIndex, List<SemanticChange>> changesByAfter = new IdentityHashMap<>();
      Map<JavaSymbolIndex, JavaSymbolIndex> matchedPairs = new IdentityHashMap<>();
      for (SemanticChange change : changes) {
        if (change.before() != null && change.after() != null) {
          matchedPairs.put(change.after(), change.before());
          changesByAfter.computeIfAbsent(change.after(), ignored -> new ArrayList<>()).add(change);
        }
      }

      Map<String, String> nextIdentities = new LinkedHashMap<>();
      for (JavaSymbolIndex symbol : current.symbols()) {
        JavaSymbolIndex before = matchedPairs.get(symbol);
        String track = before == null ? findTrack(currentTrackByIdentity, symbol) : findTrack(currentTrackByIdentity, before);
        if (track == null) {
          track = uniqueLogicalId(tracks, logicalId(symbol), current.project().commitId());
        }
        tracks.computeIfAbsent(track, ignored -> new ArrayList<>())
            .add(entry(index, current, symbol, changesByAfter.getOrDefault(symbol, List.of())));
        addIdentities(nextIdentities, symbol, track);
      }
      currentTrackByIdentity = nextIdentities;
    }

    return tracks.entrySet().stream()
        .map(entry -> new SymbolTimeline(entry.getKey(), entry.getValue()))
        .toList();
  }

  public SymbolTimeline find(List<SymbolTimeline> timelines, String identity) {
    return timelines.stream()
        .filter(timeline -> timeline.logicalId().equals(identity)
            || timeline.entries().stream().anyMatch(entry -> identities(entry.symbol()).contains(identity)))
        .findFirst()
        .orElse(null);
  }

  private static SymbolTimelineEntry entry(
      int index, JavaProjectAnalysisResult result, JavaSymbolIndex symbol, List<SemanticChange> changes) {
    return new SymbolTimelineEntry(index, result.project().commitId(), symbol, changes);
  }

  private static String findTrack(Map<String, String> tracks, JavaSymbolIndex symbol) {
    for (String identity : identities(symbol)) {
      String track = tracks.get(identity);
      if (track != null) {
        return track;
      }
    }
    return null;
  }

  private static void addIdentities(Map<String, String> map, JavaSymbolIndex symbol, String track) {
    identities(symbol).forEach(identity -> map.put(identity, track));
  }

  private static List<String> identities(JavaSymbolIndex symbol) {
    List<String> result = new ArrayList<>();
    add(result, symbol.getDeclarationBindingKey());
    add(result, symbol.getRawBindingKey());
    add(result, symbol.getStableSemanticKey());
    add(result, symbol.getSymbolKind() + ":" + symbol.getQualifiedName());
    return result;
  }

  private static void add(List<String> values, String value) {
    if (value != null && !value.isBlank()) {
      values.add(value);
    }
  }

  private static String logicalId(JavaSymbolIndex symbol) {
    if (symbol.getStableSemanticKey() != null && !symbol.getStableSemanticKey().isBlank()) {
      return symbol.getStableSemanticKey();
    }
    return symbol.getSymbolKind() + ":" + symbol.getQualifiedName();
  }

  private static String uniqueLogicalId(Map<String, ?> tracks, String base, String commitId) {
    if (!tracks.containsKey(base)) {
      return base;
    }
    int suffix = 2;
    String candidate = base + "@" + commitId;
    while (tracks.containsKey(candidate)) {
      candidate = base + "@" + commitId + "#" + suffix++;
    }
    return candidate;
  }
}
