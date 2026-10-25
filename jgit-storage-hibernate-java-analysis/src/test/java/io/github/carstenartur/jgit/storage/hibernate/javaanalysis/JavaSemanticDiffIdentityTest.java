/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaSemanticDiffIdentityTest {

  @Test
  void reservesUnchangedDeclarationsBeforeMatchingRemovedMethodsHeuristically() {
    for (var configuration : List.of(JavaAnalysisConfiguration.java21SyntaxOnly(),
        JavaAnalysisConfiguration.java21BindingAware())) {
      for (String declarations : List.of("void removed() {} void kept() {}",
          "void kept() {} void removed() {}")) {
        var changes = new JavaSemanticDiff().compare(analyze("before", declarations, configuration),
            analyze("after", "void kept() {}", configuration));
        assertEquals(1, changes.size(), changes.toString());
        assertEquals(SemanticChangeKind.REMOVED, changes.getFirst().kind());
        assertEquals("removed", changes.getFirst().before().getSimpleName());
        assertNull(changes.getFirst().after());
      }
    }
  }

  @Test
  void weakQualifiedNamesCannotStealAnUnchangedOverloadsStrongIdentity() {
    var configuration = JavaAnalysisConfiguration.java21BindingAware();
    for (String declarations : List.of("void use(int value) {} void use(String value) {}",
        "void use(String value) {} void use(int value) {}")) {
      var changes = new JavaSemanticDiff().compare(analyze("before", declarations, configuration),
          analyze("after", "void use(String value) {}", configuration));
      assertEquals(1, changes.size(), changes.toString());
      assertEquals(SemanticChangeKind.REMOVED, changes.getFirst().kind());
      assertEquals("int", changes.getFirst().before().getParameterTypes());
      assertNull(changes.getFirst().after());
    }
  }

  @Test
  void stillRecognizesAGenuineRenameWhenNoExactPairExists() {
    var configuration = JavaAnalysisConfiguration.java21BindingAware();
    var changes = new JavaSemanticDiff().compare(analyze("before", "void original() {}", configuration),
        analyze("after", "void renamed() {}", configuration));
    var rename = changes.stream().filter(change -> change.kind() == SemanticChangeKind.RENAMED)
        .findFirst().orElseThrow();
    assertEquals("original", rename.before().getSimpleName());
    assertEquals("renamed", rename.after().getSimpleName());
    assertFalse(changes.stream().anyMatch(change -> change.kind() == SemanticChangeKind.ADDED
        || change.kind() == SemanticChangeKind.REMOVED));
  }

  private static JavaProjectAnalysisResult analyze(String commit, String declarations,
      JavaAnalysisConfiguration configuration) {
    String path = "example/Example.java";
    var source = new JavaSourceSnapshot("repo", commit, commit + "-blob", path,
        "package example; class Example { " + declarations + " }");
    return new JavaProjectAnalyzer().analyze(new JavaProjectSnapshot("repo", commit, Map.of(path, source)),
        configuration);
  }
}
