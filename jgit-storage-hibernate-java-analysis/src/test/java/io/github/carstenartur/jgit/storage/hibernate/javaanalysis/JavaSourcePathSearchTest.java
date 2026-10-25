/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.Test;

class JavaSourcePathSearchTest {
  @Test
  void sourceKeepsLexicalValuesAndOnlyLowerToUpperIdentifierSynonyms() throws Exception {
    try (var analyzer = new JavaFileSearchAnalyzer()) {
      var tokens = tokens(analyzer, JavaFileSearchField.SOURCE,
          "// hiddenComment\n@Deprecated public class StringBuilder { int value2Factory = 42; XMLParser äValue; }");
      assertEquals(List.of("deprecated", "public", "class", "stringbuilder", "string", "builder",
          "int", "value2factory", "42", "xmlparser", "ävalue", "ä", "value"),
          tokens.stream().map(Token::text).toList());
      assertEquals(List.of(1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0, 0),
          tokens.stream().map(Token::increment).toList());
      assertEquals(tokens.get(3).start(), tokens.get(4).start());
      assertEquals(tokens.get(3).end(), tokens.get(5).end());
    }
  }

  @Test
  void rawLiteralContentsAndUnicodeDelimitedQuotesArePreserved() throws Exception {
    String slash = "\\";
    String quote = slash + "u0022";
    String source = "\"line\\nvalue\" '\\n' \"\" \"\"\"\nblock\\nvalue\n\"\"\" "
        + quote + "unicode\\tvalue" + quote + " a" + slash + "u0042c "
        + quote.repeat(3) + "\nencoded block\n" + quote.repeat(3);
    try (var analyzer = new JavaFileSearchAnalyzer()) {
      assertEquals(List.of("line\\nvalue", "\\n", "\nblock\\nvalue\n", "unicode\\tvalue",
          "a\\u0042c", "\nencoded block\n"),
          tokens(analyzer, JavaFileSearchField.SOURCE, source).stream().map(Token::text).toList());
    }
  }

  @Test
  void recoversFromInvalidInputAndResetsPendingPartsAndFinalOffsets() throws Exception {
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      try (var analyzer = new JavaFileSearchAnalyzer()) {
        for (String source : List.of("\"\\q\" \"\\z\" int afterError;", "\"unterminated", "/* open",
            "\\uZZZZ int later;", "a\uD800 b", "", "/// markdown\nint value;")) {
          // Recovery is best effort, but malformed input must terminate and not poison reuse.
          try { tokens(analyzer, JavaFileSearchField.SOURCE, source); }
          catch (IllegalArgumentException invalidUnicode) { assertTrue(invalidUnicode.getMessage().contains("Unicode")); }
          assertEquals(List.of("fresh"), tokens(analyzer, JavaFileSearchField.SOURCE, "fresh").stream().map(Token::text).toList());
        }
        var recovered = tokens(analyzer, JavaFileSearchField.SOURCE, "\\uZZZZ int afterError;").stream().map(Token::text).toList();
        assertTrue(recovered.containsAll(List.of("int", "aftererror")));
        assertEquals(List.of("_", "record", "sealed", "yield"),
            tokens(analyzer, JavaFileSearchField.SOURCE, "_ record sealed yield").stream().map(Token::text).toList());
        try (var stream = analyzer.tokenStream("SOURCE", "StringBuilder")) {
          stream.reset();
          assertTrue(stream.incrementToken()); // Abandon queued synonyms, then reuse the same components.
        }
        assertEquals(List.of("next"), tokens(analyzer, JavaFileSearchField.SOURCE, "next").stream().map(Token::text).toList());
        String ending = "int value; // tail\n  ";
        try (var stream = analyzer.tokenStream("SOURCE", ending)) {
          var offset = stream.addAttribute(OffsetAttribute.class);
          stream.reset();
          while (stream.incrementToken()) { }
          stream.end();
          assertEquals(ending.length(), offset.endOffset());
        }
      }
    });
  }

  @Test
  void pathSplitsSeparatorsAndCaseWithoutOriginalCompoundToken() throws Exception {
    try (var analyzer = new JavaFileSearchAnalyzer()) {
      assertEquals(List.of("src", "main", "java", "some", "file", "2", "java"),
          tokens(analyzer, JavaFileSearchField.PATH, "src/main\\java/SomeFile2.java").stream().map(Token::text).toList());
      assertEquals(List.of("a".repeat(255), "a"),
          tokens(analyzer, JavaFileSearchField.PATH, "a".repeat(256)).stream().map(Token::text).toList());
    }
  }

  @Test
  void sourceTermLimitFailsExplicitlyForBuildAndQuery() throws Exception {
    String boundary = "é".repeat(JavaFileSearchDocument.MAX_KEYWORD_UTF8_BYTES / 2);
    try (var index = JavaFileSearchIndex.build(List.of(document("one", "\"" + boundary + "\"", "A.java")))) {
      assertEquals(1, index.search(query(JavaFileSearchField.SOURCE, "\"" + boundary + "\"")).size());
      assertThrows(IllegalArgumentException.class,
          () -> index.search(query(JavaFileSearchField.SOURCE, "\"" + boundary + "x\"")));
    }
    assertThrows(IllegalArgumentException.class,
        () -> JavaFileSearchIndex.build(List.of(document("one", "\"" + boundary + "x\"", "A.java"))));
  }

  @Test
  void publicSearchUsesSourceAndExplicitPathFieldsWithConcurrentQueries() throws Exception {
    try (var index = JavaFileSearchIndex.build(List.of(
        document("one", "class StringBuilder { /* hidden */ int count; }", "src/SomeFile.java"),
        document("two", "class Other {}", "other/Else.java")));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      assertEquals(List.of("one"), index.search(query(JavaFileSearchField.SOURCE, "builder")).stream().map(JavaFileSearchHit::documentId).toList());
      assertTrue(index.search(query(JavaFileSearchField.SOURCE, "hidden")).isEmpty());
      assertEquals(List.of("one"), index.search(query(JavaFileSearchField.PATH, "some")).stream().map(JavaFileSearchHit::documentId).toList());
      var tasks = new ArrayList<java.util.concurrent.Callable<Integer>>();
      for (int i = 0; i < 24; i++) {
        var field = i % 2 == 0 ? JavaFileSearchField.SOURCE : JavaFileSearchField.PATH;
        String text = i % 2 == 0 ? "builder" : "some";
        tasks.add(() -> index.search(query(field, text)).size());
      }
      for (var result : executor.invokeAll(tasks)) assertEquals(1, result.get());
    }
    try (var index = JavaFileSearchIndex.build(List.of(new JavaFileSearchDocument(
        "repo", "id", "commit", "blob", "provenance-only.java", "", "java", Map.of())))) {
      assertTrue(index.search(query(JavaFileSearchField.PATH, "provenance")).isEmpty());
    }
  }

  private static JavaFileSearchDocument document(String id, String source, String path) {
    return new JavaFileSearchDocument("repo", id, "commit", id, path, "", "java",
        Map.of(JavaFileSearchField.SOURCE, source, JavaFileSearchField.PATH, path));
  }

  private static JavaFileSearchQuery query(JavaFileSearchField field, String text) {
    return new JavaFileSearchQuery("repo", null, null, text, Map.of(field, 1f), 0, 10);
  }

  private static List<Token> tokens(JavaFileSearchAnalyzer analyzer, JavaFileSearchField field, String source) throws Exception {
    var result = new ArrayList<Token>();
    try (var stream = analyzer.tokenStream(field.name(), source)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      var increment = stream.addAttribute(PositionIncrementAttribute.class);
      var offset = stream.addAttribute(OffsetAttribute.class);
      stream.reset();
      while (stream.incrementToken()) result.add(new Token(term.toString(), increment.getPositionIncrement(), offset.startOffset(), offset.endOffset()));
      stream.end();
    }
    return result;
  }
  private record Token(String text, int increment, int start, int end) {}
}
