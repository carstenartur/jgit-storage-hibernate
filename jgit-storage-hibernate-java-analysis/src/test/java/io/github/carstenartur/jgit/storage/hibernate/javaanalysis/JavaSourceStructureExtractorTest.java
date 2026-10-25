/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaSourceStructureExtractorTest {

  @Test
  void preservesPackageWhenNoTypeOrSymbolIsDeclared() {
    var configuration = JavaAnalysisConfiguration.java21SyntaxOnly();
    var packageInfo = new JavaSourceStructureExtractor().extract(new JavaSourceSnapshot(
        "repo", "commit", "blob", "p/package-info.java", "/** Package documentation. */ package p;"), configuration);
    assertEquals("p", packageInfo.packageName());
    assertTrue(packageInfo.types().isEmpty());
    assertEquals("nested.pkg", extract("package nested.pkg;", configuration).packageName());
    assertEquals("", extract("class A {}", configuration).packageName());
    assertEquals("", new JavaSourceStructure(List.of(), List.of(), 0, 0).packageName());
  }

  @Test
  void exposesOrderedDeclarationTypeUsesIncludingPrimitivesWithoutLocalReferences() {
    String source = """
        package p;
        class A {
          int first, second;
          A(String... names) {}
          java.util.List<String> values(int count, String names[]) {
            Exception local;
            try {} catch (IllegalArgumentException | IllegalStateException failure) {}
            return null;
          }
          void empty() {}
        }
        """;
    var result = extract(source, JavaAnalysisConfiguration.java21SyntaxOnly());
    assertEquals(List.of("FIELD", "METHOD_PARAMETER", "METHOD_PARAMETER", "METHOD_PARAMETER",
        "METHOD_RETURN", "CATCH", "METHOD_RETURN"), result.typeUses().stream().map(use -> use.role().name()).toList());
    assertEquals(List.of("int", "String", "int", "String", "java.util.List",
        "IllegalArgumentException|IllegalStateException", "void"), result.typeUses().stream().map(JavaSourceStructure.TypeUse::rawTypeName).toList());
    var parameter = result.typeUses().get(2);
    var returned = result.typeUses().get(4);
    assertEquals(parameter.ownerStartPosition(), returned.ownerStartPosition());
    assertTrue(parameter.startPosition() > returned.startPosition());
    assertEquals("java.util.List<String>", returned.sourceName());
    assertEquals("java.util.List<String>", source.substring(returned.startPosition(), returned.startPosition() + returned.sourceLength()));
    assertThrows(UnsupportedOperationException.class, () -> result.typeUses().clear());
  }

  @Test
  void exposesTypeKindsAndGenericParentSyntaxWithoutBindings() {
    String source = """
        package p;
        interface Api<T> {}
        class Base<T> {}
        class Child<T> extends Base<T> implements Api<T> {
          interface Nested extends java.io.Serializable {}
        }
        enum Mode implements Runnable { RUNNING; public void run() {} }
        record Value<T>(T value) implements Api<T> {}
        @interface Marker {}
        """;
    var result = extract(source, JavaAnalysisConfiguration.java21SyntaxOnly());
    assertEquals(0, result.errorCount());
    assertEquals(List.of("Api", "Base", "Child", "Nested", "Mode", "Value", "Marker"),
        result.types().stream().map(JavaSourceStructure.TypeDeclaration::name).toList());
    assertEquals(List.of("INTERFACE", "CLASS", "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE"),
        result.types().stream().map(type -> type.kind().name()).toList());
    var child = result.types().get(2);
    assertEquals("Base<T>", child.extendedTypes().getFirst().sourceName());
    assertEquals("Base", child.extendedTypes().getFirst().typeName());
    assertEquals("Api", child.implementedTypes().getFirst().typeName());
    assertEquals(4, child.startLine());
    assertTrue(source.substring(child.startPosition(), child.startPosition() + child.sourceLength()).startsWith("class Child"));
    assertEquals("java.io.Serializable", result.types().get(3).extendedTypes().getFirst().typeName());
    assertEquals("Api<T>", result.types().get(5).implementedTypes().getFirst().sourceName());
    assertThrows(UnsupportedOperationException.class, () -> result.types().clear());
    assertThrows(UnsupportedOperationException.class, () -> child.extendedTypes().clear());
  }

  @Test
  void excludesCommentAndStringHeadersAndReportsSyntaxProblems() {
    var result = extract("""
        // interface Fake extends Missing {}
        class Real { String fake = "class Fake extends Missing {}"; }
        """, JavaAnalysisConfiguration.java21SyntaxOnly());
    assertEquals(List.of("Real"), result.types().stream().map(JavaSourceStructure.TypeDeclaration::name).toList());
    var invalid = extract("record Value(int n) {}", new JavaAnalysisConfiguration(
        "8", BindingMode.DISABLED, List.of(), List.of(), List.of(), List.of(), false, "test"));
    assertTrue(invalid.errorCount() > 0);
    assertTrue(invalid.problemCount() >= invalid.errorCount());
  }

  @Test
  void preservesWildcardAndStaticImportFacets() {
    var result = extract("import java.util.*; import static java.util.Collections.emptyList; class A {}",
        JavaAnalysisConfiguration.java21SyntaxOnly());
    assertEquals(List.of(new JavaSourceStructure.ImportDeclaration("java.util", false, true),
        new JavaSourceStructure.ImportDeclaration("java.util.Collections.emptyList", true, false)), result.imports());
    assertThrows(UnsupportedOperationException.class, () -> result.imports().clear());
  }

  private static JavaSourceStructure extract(String source, JavaAnalysisConfiguration configuration) {
    return new JavaSourceStructureExtractor().extract(
        new JavaSourceSnapshot("repo", "commit", "blob", "p/Types.java", source), configuration);
  }
}
