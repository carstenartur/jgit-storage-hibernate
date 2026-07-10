/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SymbolTimeMachineGraphTest {

  @Test
  void tracksMovedAndChangedSymbolsAcrossCommits() {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaAnalysisConfiguration configuration = JavaAnalysisConfiguration.java21BindingAware();
    JavaProjectAnalysisResult first = analyzer.analyze(project("a", beforeSources()), configuration);
    JavaProjectAnalysisResult second = analyzer.analyze(project("b", afterSources()), configuration);

    List<SymbolTimeline> timelines = new SymbolTimeMachine().build(List.of(first, second));
    SymbolTimeline timeline = timelines.stream()
        .filter(candidate -> candidate.entries().stream()
            .anyMatch(entry -> "save".equals(entry.symbol().getSimpleName())))
        .findFirst().orElse(null);

    assertNotNull(timeline);
    assertTrue(timeline.entries().size() >= 2);
    assertTrue(timeline.latest().changesFromPrevious().stream()
        .anyMatch(change -> change.kind() == SemanticChangeKind.SIGNATURE_CHANGED
            || change.kind() == SemanticChangeKind.MOVED));
  }

  @Test
  void buildsCallsInheritanceOverridesAndGraphDelta() {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaAnalysisConfiguration configuration = JavaAnalysisConfiguration.java21BindingAware();
    JavaSoftwareGraph before = JavaSoftwareGraph.from(analyzer.analyze(project("a", beforeSources()), configuration));
    JavaSoftwareGraph after = JavaSoftwareGraph.from(analyzer.analyze(project("b", afterSources()), configuration));

    assertTrue(before.edges().stream().anyMatch(edge -> edge.kind() == JavaGraphEdgeKind.CALLS));
    assertTrue(before.edges().stream().anyMatch(edge -> edge.kind() == JavaGraphEdgeKind.IMPLEMENTS));
    assertTrue(before.edges().stream().anyMatch(edge -> edge.kind() == JavaGraphEdgeKind.OVERRIDES));
    assertFalse(JavaGraphDelta.between(before, after).added().isEmpty()
        && JavaGraphDelta.between(before, after).removed().isEmpty());

    JavaGraphEdge saveCall = before.edges().stream()
        .filter(edge -> edge.kind() == JavaGraphEdgeKind.CALLS)
        .filter(edge -> edge.targetSemanticKey().contains("save"))
        .findFirst().orElseThrow();
    assertFalse(before.transitiveImpact(saveCall.targetSemanticKey(), 2).isEmpty());
  }

  private static JavaProjectSnapshot project(String commit, Map<String, String> sources) {
    Map<String, JavaSourceSnapshot> snapshots = new LinkedHashMap<>();
    sources.forEach((path, source) -> snapshots.put(path,
        new JavaSourceSnapshot("demo", commit, Integer.toHexString(source.hashCode()), path, source)));
    return new JavaProjectSnapshot("demo", commit, snapshots);
  }

  private static Map<String, String> beforeSources() {
    return Map.of(
        "src/main/java/demo/StoreApi.java", """
            package demo;
            public interface StoreApi { String save(String value); }
            """,
        "src/main/java/demo/Store.java", """
            package demo;
            public class Store implements StoreApi {
              public String save(String value) { return value; }
            }
            """,
        "src/main/java/demo/Client.java", """
            package demo;
            public class Client {
              public String run(Store store) { return store.save("x"); }
            }
            """);
  }

  private static Map<String, String> afterSources() {
    return Map.of(
        "src/main/java/demo/StoreApi.java", """
            package demo;
            public interface StoreApi { CharSequence save(CharSequence value); }
            """,
        "src/main/java/demo/persistence/Store.java", """
            package demo.persistence;
            import demo.StoreApi;
            public class Store implements StoreApi {
              public CharSequence save(CharSequence value) { return value; }
            }
            """,
        "src/main/java/demo/Client.java", """
            package demo;
            import demo.persistence.Store;
            public class Client {
              public CharSequence run(Store store) { return store.save("x"); }
            }
            """);
  }
}
