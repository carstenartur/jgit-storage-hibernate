/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaFileSearchField.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaFileSearchIndexTest {

  @Test
  void preservesFirstBlobProvenanceAndRepositoryIsolation() throws Exception {
    var first = document("repo", "blob", "old", "old/Type.java", "module", Map.of(METHODS, "buildValue"));
    var duplicate = document("repo", "blob", "new", "moved/Type.java", "other", Map.of(METHODS, "different"));
    var other = document("other", "blob", "other", "other/Type.java", "module", Map.of(METHODS, "buildValue"));
    try (var index = JavaFileSearchIndex.build(List.of(first, duplicate, other))) {
      var hits = index.search(query("repo", null, "build", Map.of(METHODS, 1f), 0, 10));
      assertEquals(1, hits.size());
      assertEquals("old", hits.getFirst().commitId());
      assertEquals("old/Type.java", hits.getFirst().path());
      assertEquals("blob", hits.getFirst().blobId());
      assertEquals("repo", hits.getFirst().repositoryName());
      assertTrue(index.search(query("repo", null, "different", Map.of(METHODS, 1f), 0, 10)).isEmpty());
      assertEquals(1, index.search(query("other", null, "build", Map.of(METHODS, 1f), 0, 10)).size());
    }
  }

  @Test
  void filtersBeforeStablePagingAndHandlesVeryDeepOffsets() throws Exception {
    List<JavaFileSearchDocument> documents = new ArrayList<>();
    for (int i = 720; i >= 0; i--) {
      documents.add(document("repo", "%03d".formatted(i), "commit", "Type.java", i % 2 == 0 ? "chosen" : "other",
          Map.of(METHODS, i % 4 == 0 ? "value" : "value value value")));
    }
    documents.add(new JavaFileSearchDocument("repo", "excluded", "c", "blob", "file.xml", "chosen", "xml", Map.of(METHODS, "value")));
    try (var index = JavaFileSearchIndex.build(documents)) {
      var query = new JavaFileSearchQuery("repo", "chosen", "java", "value", Map.of(METHODS, 1f), 0, 1000);
      var all = index.search(query);
      assertEquals(361, all.size());
      assertTrue(all.stream().map(JavaFileSearchHit::score).distinct().count() > 1);
      assertEquals(all.stream().sorted(java.util.Comparator.comparingDouble(JavaFileSearchHit::score).reversed()
          .thenComparing(JavaFileSearchHit::documentId)).toList(), all);
      assertTrue(all.stream().allMatch(hit -> "chosen".equals(hit.module()) && "java".equals(hit.fileType()) && hit.score() > 0));
      assertEquals(all.subList(257, 274), index.search(new JavaFileSearchQuery("repo", "chosen", "java", "value", Map.of(METHODS, 1f), 257, 17)));
      assertTrue(index.search(query("repo", "chosen", "value", Map.of(METHODS, 1f), Integer.MAX_VALUE, 1)).isEmpty());
      assertTrue(index.search(new JavaFileSearchQuery("repo", null, "binary", "value", Map.of(METHODS, 1f), 0, 10)).isEmpty());
    }
  }

  @Test
  void retainsQualifiedFieldCardinalityAndAnalyzesIdentifiersAndText() throws Exception {
    try (var index = JavaFileSearchIndex.build(List.of(
        document("repo", "one", "c", "One.java", "m", Map.of(QUALIFIED_NAMES, "p.StringBuilder", METHODS, "createValue",
            DOCUMENTATION, "The StringBuilder documentation", STRING_LITERALS, "User-facing message")),
        document("repo", "multi", "c", "Multi.java", "m", Map.of(QUALIFIED_NAMES, "p.First\np.Second"))))) {
      assertEquals(List.of("one"), ids(index.search(query("repo", null, "P.StringBuilder", Map.of(QUALIFIED_NAMES, 1f), 0, 10))));
      assertTrue(index.search(query("repo", null, "StringBuilder", Map.of(QUALIFIED_NAMES, 1f), 0, 10)).isEmpty());
      assertTrue(index.search(query("repo", null, "p.First", Map.of(QUALIFIED_NAMES, 1f), 0, 10)).isEmpty());
      assertEquals(List.of("multi"), ids(index.search(query("repo", null, "p.First\np.Second", Map.of(QUALIFIED_NAMES, 1f), 0, 10))));
      assertEquals(List.of("one"), ids(index.search(query("repo", null, "value", Map.of(METHODS, 1f), 0, 10))));
      assertEquals(List.of("one"), ids(index.search(query("repo", null, "createValue", Map.of(METHODS, 1f), 0, 10))));
      assertEquals(List.of("one"), ids(index.search(query("repo", null, "builder", Map.of(DOCUMENTATION, 1f), 0, 10))));
      assertEquals(List.of("one"), ids(index.search(query("repo", null, "message", Map.of(STRING_LITERALS, 1f), 0, 10))));
      assertTrue(index.search(query("repo", null, "the", Map.of(DOCUMENTATION, 1f), 0, 10)).isEmpty());
      assertTrue(index.search(query("repo", null, " ", Map.of(METHODS, 1f), 0, 10)).isEmpty());
    }
  }

  @Test
  void appliesFieldBoostsAndTreatsOperatorsAsLiteralText() throws Exception {
    try (var index = JavaFileSearchIndex.build(List.of(
        document("repo", "method", "c", "Method.java", "m", Map.of(METHODS, "needle")),
        document("repo", "field", "c", "Field.java", "m", Map.of(FIELDS, "needle"))))) {
      assertEquals(List.of("method", "field"), ids(index.search(query("repo", null, "needle", Map.of(METHODS, 2f, FIELDS, 1f), 0, 10))));
      assertEquals(List.of("field", "method"), ids(index.search(query("repo", null, "needle", Map.of(METHODS, 1f, FIELDS, 2f), 0, 10))));
      assertEquals(List.of("method"), ids(index.search(query("repo", null, "-needle", Map.of(METHODS, 1f), 0, 10))));
      assertTrue(index.search(query("repo", null, "*", Map.of(METHODS, 1f), 0, 10)).isEmpty());
    }
  }

  @Test
  void preservesCompleteCompoundPathsWhilePlainTermsRemainDisjunctive() throws Exception {
    try (var index = JavaFileSearchIndex.build(List.of(
        document("repo", "compound", "c", "Compound.java", "m", Map.of(METHODS, "createValue")),
        document("repo", "part", "c", "Part.java", "m", Map.of(METHODS, "value"))))) {
      assertEquals(List.of("compound"), ids(index.search(query("repo", null, "createValue", Map.of(METHODS, 1f), 0, 10))));
      assertEquals(java.util.Set.of("compound", "part"), java.util.Set.copyOf(ids(index.search(
          query("repo", null, "create value", Map.of(METHODS, 1f), 0, 10)))));
    }
  }

  @Test
  void validatesWholeKeywordByteLimitsWithoutSplittingOrTruncatingFacets() throws Exception {
    String maximum = "é".repeat(16_383);
    var accepted = document("repo", "id", "c", "Type.java", "m", Map.of(QUALIFIED_NAMES, maximum));
    try (var index = JavaFileSearchIndex.build(List.of(accepted))) {
      assertEquals(List.of("id"), ids(index.search(query("repo", null, maximum, Map.of(QUALIFIED_NAMES, 1f), 0, 10))));
    }
    assertThrows(IllegalArgumentException.class,
        () -> document("repo", "id", "c", "Type.java", "m", Map.of(QUALIFIED_NAMES, maximum + "x")));
    // U+023A lowercases to U+2C65: two UTF-8 input bytes become three indexed bytes.
    assertThrows(IllegalArgumentException.class,
        () -> document("repo", "id", "c", "Type.java", "m", Map.of(QUALIFIED_NAMES, "\u023A".repeat(10_923))));
    assertThrows(IllegalArgumentException.class,
        () -> document("repo", "x".repeat(32_767), "c", "Type.java", "m", Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> document("repo", "broken\uD800", "c", "Type.java", "m", Map.of()));
  }

  @Test
  void acceptsPublicAnalysisFacetsWithoutPersistenceOrBackendTypes() throws Exception {
    var snapshot = new JavaSourceSnapshot("repo", "commit", "blob", "p/Type.java", """
        package p;
        /** Builds messages. */
        public class Type { String createValue() { return "Unique greeting"; } }
        """);
    var configuration = JavaAnalysisConfiguration.java21SyntaxOnly();
    var analysis = new JavaJdtAnalyzer().analyze(snapshot, configuration);
    var text = new JavaSourceTextExtractor().extract(snapshot, configuration);
    String methods = analysis.symbols().stream().filter(symbol -> symbol.getSymbolKind() == JavaSymbolKind.METHOD)
        .map(symbol -> symbol.getSimpleName()).collect(java.util.stream.Collectors.joining("\n"));
    String literals = text.stringLiterals().stream().map(fragment -> fragment.text()).collect(java.util.stream.Collectors.joining("\n"));
    try (var index = JavaFileSearchIndex.build(List.of(document("repo", "blob", "commit", snapshot.path(), "m",
        Map.of(METHODS, methods, STRING_LITERALS, literals))))) {
      assertEquals(1, index.search(query("repo", null, "value", Map.of(METHODS, 1f), 0, 10)).size());
      assertEquals(1, index.search(query("repo", null, "greeting", Map.of(STRING_LITERALS, 1f), 0, 10)).size());
    }
  }

  @Test
  void rejectsIncompleteInputAndSupportsConcurrentReaders() throws Exception {
    var document = document("repo", "id", "c", "Type.java", "m", Map.of(METHODS, "buildValue"));
    Iterable<JavaFileSearchDocument> broken = () -> new java.util.Iterator<>() {
      private boolean first = true;
      @Override public boolean hasNext() {
        if (!first) { throw new IllegalStateException("Input generation failed"); }
        return true;
      }
      @Override public JavaFileSearchDocument next() { first = false; return document; }
    };
    assertEquals("Input generation failed", assertThrows(IllegalStateException.class,
        () -> JavaFileSearchIndex.build(broken)).getMessage());
    try (var index = JavaFileSearchIndex.build(List.of(document));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      List<java.util.concurrent.Callable<List<JavaFileSearchHit>>> readers = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        readers.add(() -> index.search(query("repo", null, "build", Map.of(METHODS, 1f), 0, 10)));
      }
      for (var result : executor.invokeAll(readers)) {
        assertEquals(List.of("id"), ids(result.get()));
      }
    }
    try (var empty = JavaFileSearchIndex.build(List.of())) {
      assertTrue(empty.search(query("repo", null, "text", Map.of(METHODS, 1f), 0, 10)).isEmpty());
    }
  }

  @Test
  void concurrentCloseAllowsCompleteResultsOrTheDocumentedClosedState() throws Exception {
    for (int run = 0; run < 5; run++) {
      try (var index = JavaFileSearchIndex.build(List.of(
          document("repo", "one", "c", "One.java", "m", Map.of(METHODS, "value")),
          document("repo", "two", "c", "Two.java", "m", Map.of(METHODS, "value"))));
          var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
        var ready = new java.util.concurrent.CountDownLatch(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
          boolean closing = i == 0;
          results.add(executor.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
            if (closing) {
              index.close();
            } else {
              try {
                assertEquals(List.of("one", "two"), ids(index.search(query("repo", null, "value", Map.of(METHODS, 1f), 0, 10))));
              } catch (IllegalStateException closed) {
                assertEquals(IllegalStateException.class, closed.getClass());
                assertEquals("Java file search index is closed", closed.getMessage());
              }
            }
            return null;
          }));
        }
        try {
          assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
          start.countDown();
          for (var result : results) {
            result.get(10, java.util.concurrent.TimeUnit.SECONDS);
          }
        } finally {
          start.countDown();
        }
      }
    }
  }

  @Test
  void validatesConfigurationCopiesCollectionsAndClosesIdempotently() throws Exception {
    var fields = new LinkedHashMap<JavaFileSearchField, String>();
    fields.put(METHODS, "original");
    var document = document("repo", "id", "c", "Type.java", "m", fields);
    fields.put(METHODS, "changed");
    assertEquals("original", document.fields().get(METHODS));
    assertThrows(UnsupportedOperationException.class, () -> document.fields().put(FIELDS, "x"));
    for (float invalid : List.of(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertThrows(IllegalArgumentException.class, () -> query("repo", null, "text", Map.of(METHODS, invalid), 0, 10));
    }
    assertThrows(IllegalArgumentException.class, () -> query("repo", null, "text", Map.of(), 0, 10));
    assertThrows(IllegalArgumentException.class, () -> query("repo", null, "text", Map.of(METHODS, 1f), -1, 10));
    assertThrows(IllegalArgumentException.class, () -> query("repo", null, "text", Map.of(METHODS, 1f), 0, 1001));
    assertThrows(IllegalArgumentException.class, () -> document(" ", "id", "c", "Type.java", "m", Map.of()));
    var index = JavaFileSearchIndex.build(List.of(document));
    assertTrue(index.search(query("repo", null, null, Map.of(METHODS, 1f), 0, 10)).isEmpty());
    index.close();
    index.close();
    assertThrows(IllegalStateException.class, () -> index.search(query("repo", null, "original", Map.of(METHODS, 1f), 0, 10)));
  }

  private static JavaFileSearchDocument document(String repo, String id, String commit, String path,
      String module, Map<JavaFileSearchField, String> fields) {
    return new JavaFileSearchDocument(repo, id, commit, id, path, module, "java", fields);
  }

  private static JavaFileSearchQuery query(String repo, String module, String text,
      Map<JavaFileSearchField, Float> boosts, int offset, int limit) {
    return new JavaFileSearchQuery(repo, module, null, text, boosts, offset, limit);
  }

  private static List<String> ids(List<JavaFileSearchHit> hits) {
    return hits.stream().map(JavaFileSearchHit::documentId).toList();
  }
}
