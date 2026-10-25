/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaDeclarationCompatibilityTest {

  @Test
  void includesEnumConstantsAsFieldsWithStableIdentityAndLocations() {
    JavaSourceSnapshot source = new JavaSourceSnapshot("repo", "commit", "blob", "p/Mode.java", """
        package p;
        enum Mode {
          @Deprecated RUNNING, STOPPED;
        }
        """);
    for (var configuration : List.of(JavaAnalysisConfiguration.java21SyntaxOnly(),
        JavaAnalysisConfiguration.java21BindingAware())) {
      var result = new JavaJdtAnalyzer().analyze(source, configuration);
      var constants = result.symbols().stream().filter(s -> s.getSymbolKind() == JavaSymbolKind.FIELD).toList();
      assertEquals(List.of("RUNNING", "STOPPED"), constants.stream().map(s -> s.getSimpleName()).toList());
      var running = constants.getFirst();
      assertEquals("p.Mode", running.getDeclaringType());
      assertEquals("p.Mode#RUNNING", running.getQualifiedName());
      assertEquals("FIELD:p.Mode#RUNNING:" + (configuration.resolveBindings() ? "p.Mode" : ""),
          running.getStableSemanticKey());
      assertTrue(running.getAnnotations().contains("Deprecated"));
      assertEquals(3, running.getStartLine());
      assertTrue(running.getSourceLength() > 0);
      assertEquals(configuration.resolveBindings() ? BindingStatus.FULL : BindingStatus.NONE,
          running.getBindingStatus());
    }
  }

  @Test
  void includesGenericClassAndRecordSupertypesAcrossPackages() {
    var analysis = analyze(Map.of(
        "parent/Base.java", "package parent; public class Base<T> {}",
        "p/Api.java", "package p; public interface Api<T> {}",
        "p/Child.java", "package p; import parent.Base; class Child<T extends Number> extends Base<T> implements Api<T> {}",
        "p/Value.java", "package p; record Value<T>(T value) implements Api<T> {}"));
    var graph = JavaSoftwareGraph.from(analysis);
    assertEdge(graph, "p.Child", "parent.Base", JavaGraphEdgeKind.EXTENDS);
    assertEdge(graph, "p.Child", "p.Api", JavaGraphEdgeKind.IMPLEMENTS);
    assertEdge(graph, "p.Value", "p.Api", JavaGraphEdgeKind.IMPLEMENTS);
  }

  @Test
  void distinguishesNestedDeclarationsAndIgnoresFakeHeadersInCommentsAndStrings() {
    var analysis = analyze(Map.of("p/Outer.java", """
        package p;
        interface A<T> {}
        interface B<T> {}
        interface C<T> extends A<T>, B<T> {}
        class Parent {}
        class Outer {
          // class Inner extends Parent {}
          String fake = "class Inner extends Parent {}";
          class Inner<T> implements A<T> {}
        }
        class Other { class Inner extends Parent {} }
        """));
    var graph = JavaSoftwareGraph.from(analysis);
    assertEdge(graph, "p.C", "p.A", JavaGraphEdgeKind.EXTENDS);
    assertEdge(graph, "p.C", "p.B", JavaGraphEdgeKind.EXTENDS);
    assertEdge(graph, "p.Outer.Inner", "p.A", JavaGraphEdgeKind.IMPLEMENTS);
    assertEdge(graph, "p.Other.Inner", "p.Parent", JavaGraphEdgeKind.EXTENDS);
    assertFalse(graph.edges().stream().anyMatch(edge -> edge.kind() == JavaGraphEdgeKind.EXTENDS
        && edge.sourceSemanticKey().equals(key(graph, "p.Outer.Inner"))));
  }

  @Test
  void retainsUnresolvedGenericParentWithoutInventingResolvedBindings() {
    var analysis = analyze(Map.of("p/Child.java", "package p; class Child<T> extends missing.Base<T> {}"));
    var edges = JavaSoftwareGraph.from(analysis).edges().stream()
        .filter(edge -> edge.kind() == JavaGraphEdgeKind.EXTENDS).toList();
    assertEquals(1, edges.size());
    assertTrue(edges.getFirst().targetSemanticKey().contains("missing.Base"), edges.toString());
    assertFalse(edges.getFirst().bindingStatus() == BindingStatus.FULL);
  }

  @Test
  void syntaxOnlyResolvesEnclosingScopesBeforePackageTypes() {
    var graph = JavaSoftwareGraph.from(analyze(Map.of("p/Outer.java", """
        package p;
        class Base {}
        class Outer {
          class Base {}
          class Middle { class Child extends Base {} }
        }
        """), JavaAnalysisConfiguration.java21SyntaxOnly()));
    assertEdge(graph, "p.Outer.Middle.Child", "p.Outer.Base", JavaGraphEdgeKind.EXTENDS);
    assertFalse(graph.edges().stream().anyMatch(edge -> edge.kind() == JavaGraphEdgeKind.EXTENDS
        && edge.targetSemanticKey().equals(key(graph, "p.Base"))));
  }

  @Test
  void syntaxOnlyHonorsExplicitImportsAndDoesNotGuessUnimportedTypes() {
    var graph = JavaSoftwareGraph.from(analyze(Map.of(
        "p/Base.java", "package p; class Base {}",
        "q/Foreign.java", "package q; public class Foreign {}",
        "p/Child.java", "package p; import missing.Base; class Child extends Base {}",
        "p/Other.java", "package p; class Other extends Foreign {}"), JavaAnalysisConfiguration.java21SyntaxOnly()));
    var edges = graph.edges().stream().filter(edge -> edge.kind() == JavaGraphEdgeKind.EXTENDS).toList();
    assertEquals(2, edges.size());
    assertTrue(edges.stream().anyMatch(edge -> edge.targetSemanticKey().equals("TYPE:missing.Base:")));
    assertFalse(edges.stream().anyMatch(edge -> edge.targetSemanticKey().equals(key(graph, "q.Foreign"))));
  }

  @Test
  void wildcardImportsDoNotShadowPackageTypesWithTheSameLastName() {
    var graph = JavaSoftwareGraph.from(analyze(Map.of("p/Types.java",
        "package p; import java.util.*; class util {} class Child extends util {}"),
        JavaAnalysisConfiguration.java21SyntaxOnly()));
    assertEdge(graph, "p.Child", "p.util", JavaGraphEdgeKind.EXTENDS);
  }

  @Test
  void staticFieldImportsDoNotBecomeTypesButStaticNestedTypesDo() {
    for (boolean nestedType : List.of(false, true)) {
      var graph = JavaSoftwareGraph.from(analyze(Map.of(
          "p/Base.java", "package p; public class Base {}",
          "q/Values.java", "package q; public class Values { public static "
              + (nestedType ? "class Base {}" : "int Base;") + " }",
          "p/Child.java", "package p; import static q.Values.Base; class Child extends Base {}"),
          JavaAnalysisConfiguration.java21SyntaxOnly()));
      assertEdge(graph, "p.Child", nestedType ? "q.Values.Base" : "p.Base", JavaGraphEdgeKind.EXTENDS);
    }
  }

  @Test
  void namedPackagesNeverResolveDefaultPackageParents() {
    for (boolean samePackageType : List.of(false, true)) {
      Map<String, String> sources = new LinkedHashMap<>();
      sources.put("Base.java", "public class Base {}");
      sources.put("p/Child.java", "package p; class Child extends Base {}");
      if (samePackageType) {
        sources.put("p/Base.java", "package p; public class Base {}");
      }
      var graph = JavaSoftwareGraph.from(analyze(sources, JavaAnalysisConfiguration.java21SyntaxOnly()));
      var edge = graph.edges().stream().filter(e -> e.kind() == JavaGraphEdgeKind.EXTENDS).findFirst().orElseThrow();
      assertFalse(edge.targetSemanticKey().equals(key(graph, "Base")), "Default-package type is inaccessible");
      assertEquals(BindingStatus.PARTIAL, edge.bindingStatus());
      if (samePackageType) {
        assertEdge(graph, "p.Child", "p.Base", JavaGraphEdgeKind.EXTENDS);
      }
    }
  }

  @Test
  void syntaxOnlyResolvesUniqueOrdinaryAndStaticWildcardImports() {
    for (boolean staticImport : List.of(false, true)) {
      var graph = JavaSoftwareGraph.from(analyze(Map.of(
          "q/Base.java", "package q; public class Base {}",
          "q/Types.java", "package q; public class Types { public static class Base {} }",
          "p/Child.java", "package p; import " + (staticImport ? "static q.Types.*;" : "q.*;")
              + " class Child extends Base {}"), JavaAnalysisConfiguration.java21SyntaxOnly()));
      assertEdge(graph, "p.Child", staticImport ? "q.Types.Base" : "q.Base", JavaGraphEdgeKind.EXTENDS);
      assertEquals(BindingStatus.PARTIAL, graph.edges().stream()
          .filter(e -> e.kind() == JavaGraphEdgeKind.EXTENDS).findFirst().orElseThrow().bindingStatus());
    }
  }

  @Test
  void ambiguousWildcardImportsRemainUnresolved() {
    var graph = JavaSoftwareGraph.from(analyze(Map.of(
        "q/Base.java", "package q; public class Base {}",
        "r/Base.java", "package r; public class Base {}",
        "p/Child.java", "package p; import q.*; import r.*; class Child extends Base {}"),
        JavaAnalysisConfiguration.java21SyntaxOnly()));
    var edge = graph.edges().stream().filter(e -> e.kind() == JavaGraphEdgeKind.EXTENDS).findFirst().orElseThrow();
    assertEquals(BindingStatus.PARTIAL, edge.bindingStatus());
    assertFalse(edge.targetSemanticKey().equals(key(graph, "q.Base")));
    assertFalse(edge.targetSemanticKey().equals(key(graph, "r.Base")));
  }

  private static JavaProjectAnalysisResult analyze(Map<String, String> sources) {
    return analyze(sources, JavaAnalysisConfiguration.java21BindingAware());
  }

  private static JavaProjectAnalysisResult analyze(Map<String, String> sources, JavaAnalysisConfiguration configuration) {
    Map<String, JavaSourceSnapshot> files = new LinkedHashMap<>();
    sources.forEach((path, source) -> files.put(path,
        new JavaSourceSnapshot("repo", "commit", Integer.toHexString(source.hashCode()), path, source)));
    return new JavaProjectAnalyzer().analyze(new JavaProjectSnapshot("repo", "commit", files), configuration);
  }

  private static void assertEdge(JavaSoftwareGraph graph, String child, String parent, JavaGraphEdgeKind kind) {
    assertTrue(graph.edges().stream().anyMatch(edge -> edge.kind() == kind
        && edge.sourceSemanticKey().equals(key(graph, child))
        && edge.targetSemanticKey().equals(key(graph, parent))), child + " -> " + parent + ": " + kind);
  }

  private static String key(JavaSoftwareGraph graph, String name) {
    return graph.symbols().values().stream().filter(symbol -> name.equals(symbol.getQualifiedName()))
        .findFirst().orElseThrow().getStableSemanticKey();
  }
}
