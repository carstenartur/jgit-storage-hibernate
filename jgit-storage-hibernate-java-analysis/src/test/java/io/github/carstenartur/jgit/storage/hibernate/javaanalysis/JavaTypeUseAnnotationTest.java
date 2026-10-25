/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaTypeUseAnnotationTest {
  @Test void visitsAnnotationsInsideSuppressedTypeSubtreesExactlyOnce() {
    String source = """
        package p;
        import java.util.List;
        class Outer<T> { class Inner<U> {} }
        class Example<T extends @Mark Number> {
          List<@Mark String> values;
          Outer<@Mark String>.@Mark Inner<@Mark Integer> nested;
          @Mark String @Mark [] dimensions;
          void use(@Mark String arg) { @Mark String local = null; Object value = (@Mark String) local; }
        }
        """;
    var result = new JavaJdtAnalyzer().analyze(new JavaSourceSnapshot("repo", "commit", "blob", "p/Example.java", source),
        JavaAnalysisConfiguration.java21SyntaxOnly());
    var annotations = result.references().stream().filter(r -> r.getReferenceKind() == JavaReferenceKind.ANNOTATION_USE).toList();
    assertEquals(source.split("@Mark", -1).length - 1, annotations.size());
    assertEquals(annotations.size(), annotations.stream().map(r -> r.getStartPosition()).distinct().count());
    assertTrue(annotations.stream().allMatch(r -> r.getReferenceName().equals("Mark")));
    assertTrue(annotations.stream().allMatch(r -> source.substring(r.getStartPosition(), r.getStartPosition() + r.getSourceLength()).equals("@Mark")));
    var types = result.references().stream().filter(r -> r.getReferenceKind() == JavaReferenceKind.TYPE_REFERENCE).toList();
    assertEquals(10, types.size()); // Whole qualified types remain one reference; no qualifier duplication.
  }

  @Test void includesNestedAnnotationValuesWithoutRepeatingTheOuterAnnotation() {
    String source = "class Example { java.util.List<@Outer(@Inner) String> values; }";
    var result = new JavaJdtAnalyzer().analyze(new JavaSourceSnapshot("repo", "commit", "blob", "Example.java", source),
        JavaAnalysisConfiguration.java21SyntaxOnly());
    assertEquals(List.of("Outer", "Inner"), result.references().stream()
        .filter(r -> r.getReferenceKind() == JavaReferenceKind.ANNOTATION_USE).map(r -> r.getReferenceName()).toList());
  }
}
