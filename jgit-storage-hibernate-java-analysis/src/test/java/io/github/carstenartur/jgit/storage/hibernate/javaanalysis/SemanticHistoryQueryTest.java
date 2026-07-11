/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticHistoryQueryTest {

  @Test
  void symbolsOfKindFiltersCorrectly() {
    JavaProjectAnalysisResult before = analyze(beforeSources(), "before");

    var methods = new SemanticHistoryQuery(before).symbolsOfKind(JavaSymbolKind.METHOD);

    assertFalse(methods.isEmpty());
    assertTrue(methods.stream().allMatch(symbol -> symbol.getSymbolKind() == JavaSymbolKind.METHOD));
  }

  @Test
  void changesOfKindFiltersSemanticDiff() {
    JavaProjectAnalysisResult before = analyze(beforeSources(), "before");
    JavaProjectAnalysisResult after = analyze(afterSources(), "after");

    var changes = new SemanticHistoryQuery(before).changesOfKind(after, SemanticChangeKind.SIGNATURE_CHANGED);

    assertFalse(changes.isEmpty());
    assertTrue(changes.stream().allMatch(change -> change.kind() == SemanticChangeKind.SIGNATURE_CHANGED));
  }

  @Test
  void movedSymbolsDetectsPackageMove() {
    JavaProjectAnalysisResult before = analyze(beforeSources(), "before");
    JavaProjectAnalysisResult after = analyze(afterSources(), "after");

    var moved = new SemanticHistoryQuery(before).movedSymbols(after);

    assertTrue(moved.stream().anyMatch(symbol -> "Store".equals(symbol.getSimpleName())
        && symbol.getPath().contains("demo/persistence/Store.java")));
  }

  @Test
  void symbolsPageReturnsTotalCountAndCorrectSlice() {
    JavaProjectAnalysisResult before = analyze(beforeSources(), "before");
    SemanticHistoryQuery query = new SemanticHistoryQuery(before);

    var page = query.symbolsPage(symbol -> true, 1, 2);

    assertEquals(before.symbols().size(), page.totalCount());
    assertEquals(1, page.offset());
    assertEquals(2, page.pageSize());
    assertEquals(Math.min(2, before.symbols().size() - 1), page.items().size());
  }

  @Test
  void symbolsWithBindingFiltersPartialAndFull() {
    JavaProjectAnalysisResult result = syntheticResult();

    var full = new SemanticHistoryQuery(result).symbolsWithBinding(BindingStatus.FULL);
    var partialOrBetter = new SemanticHistoryQuery(result).symbolsWithBinding(BindingStatus.PARTIAL);

    assertFalse(full.isEmpty());
    assertTrue(full.stream().allMatch(symbol -> symbol.getBindingStatus() == BindingStatus.FULL));
    assertEquals(3, partialOrBetter.size());
    assertTrue(partialOrBetter.stream().allMatch(symbol -> symbol.getBindingStatus() != BindingStatus.NONE));
    assertTrue(partialOrBetter.stream().anyMatch(symbol -> symbol.getBindingStatus() == BindingStatus.PARTIAL
        || symbol.getBindingStatus() == BindingStatus.RECOVERED));
  }

  private static JavaProjectAnalysisResult analyze(Map<String, String> sources, String commitId) {
    return new JavaProjectAnalyzer().analyze(project(commitId, sources), JavaAnalysisConfiguration.java21BindingAware());
  }

  private static JavaProjectSnapshot project(String commitId, Map<String, String> sources) {
    Map<String, JavaSourceSnapshot> snapshots = new LinkedHashMap<>();
    sources.forEach((path, text) -> snapshots.put(path,
        new JavaSourceSnapshot("repo", commitId, Integer.toHexString(text.hashCode()), path, text)));
    return new JavaProjectSnapshot("repo", commitId, snapshots);
  }

  private static Map<String, String> beforeSources() {
    return Map.of(
        "src/main/java/demo/Store.java", """
            package demo;
            public class Store {
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
        "src/main/java/demo/persistence/Store.java", """
            package demo.persistence;
            public class Store {
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

  private static Map<String, String> unresolvedSources() {
    return Map.of(
        "src/main/java/demo/BrokenClient.java", """
            package demo;
            import missing.ExternalType;
            public class BrokenClient {
              public ExternalType call(ExternalType value) { return value; }
            }
            """);
  }

  private static JavaProjectAnalysisResult syntheticResult() {
    JavaProjectSnapshot project = new JavaProjectSnapshot("repo", "synthetic", Map.of());
    return new JavaProjectAnalysisResult(
        project,
        Map.of(),
        List.of(
            symbol("none", BindingStatus.NONE),
            symbol("partial", BindingStatus.PARTIAL),
            symbol("recovered", BindingStatus.RECOVERED),
            symbol("full", BindingStatus.FULL)),
        List.of(),
        0,
        0);
  }

  private static JavaSymbolIndex symbol(String simpleName, BindingStatus status) {
    JavaSymbolIndex symbol = new JavaSymbolIndex();
    symbol.setRepositoryName("repo");
    symbol.setCommitId("synthetic");
    symbol.setBlobId(simpleName);
    symbol.setPath("src/main/java/demo/" + simpleName + ".java");
    symbol.setSymbolKind(JavaSymbolKind.METHOD);
    symbol.setBindingStatus(status);
    symbol.setSimpleName(simpleName);
    symbol.setQualifiedName("demo.Sample#" + simpleName);
    symbol.setStableSemanticKey("METHOD:demo.Sample#" + simpleName + ":()");
    symbol.setStartLine(1);
    symbol.setEndLine(1);
    return symbol;
  }
}
