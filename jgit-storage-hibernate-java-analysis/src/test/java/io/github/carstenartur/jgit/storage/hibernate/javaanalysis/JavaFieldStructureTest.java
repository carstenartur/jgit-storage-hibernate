/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaFieldStructureTest {
  @Test void preservesFieldDeclarationGroupsBeforeAnonymousInitializerBodies() {
    String source = "enum Choice { FIRST { int nested; }, SECOND; } "
        + "class Example { Object first = new Object() { int deep; }, second; }";
    var result = extract(source);
    assertEquals(List.of("FIRST", "SECOND", "nested", "first", "second", "deep"),
        result.fields().stream().map(JavaSourceStructure.FieldDeclaration::name).toList());
    assertTrue(result.fields().get(0).enumConstant());
    assertTrue(result.fields().get(1).enumConstant());
    assertFalse(result.fields().get(2).enumConstant());
    assertEquals(source.indexOf("enum Choice"), result.fields().get(0).declarationStartPosition());
    assertEquals(result.fields().get(0).declarationStartPosition(), result.fields().get(1).declarationStartPosition());
    assertEquals(result.fields().get(3).declarationStartPosition(), result.fields().get(4).declarationStartPosition());
    assertEquals(source.indexOf("Object first"), result.fields().get(3).declarationStartPosition());
    assertTrue(result.fields().stream().allMatch(f -> source.substring(f.startPosition(), f.startPosition() + f.sourceLength()).startsWith(f.name())));
    assertThrows(UnsupportedOperationException.class, () -> result.fields().clear());
    assertTrue(new JavaSourceStructure(List.of(), List.of(), 0, 0).fields().isEmpty());
    assertTrue(new JavaSourceStructure(List.of(), List.of(), 0, 0, "p", List.of()).fields().isEmpty());
  }

  @Test void exposesRawParentsWithoutChangingNormalizedNames() {
    var result = extract("class First extends Outer<String>.Inner {} "
        + "class Second extends @Mark Base<String> {} "
        + "class Third extends Outer<String>.@Mark Inner<Integer> {}");
    var parents = result.types().stream().flatMap(t -> t.extendedTypes().stream()).toList();
    assertEquals(List.of("Outer.Inner", "Base", "Outer.Inner"), parents.stream().map(JavaSourceStructure.ParentType::typeName).toList());
    assertEquals(List.of("Outer<String>.Inner", "Base", "Outer<String>.@Mark Inner"),
        parents.stream().map(JavaSourceStructure.ParentType::rawTypeName).toList());
    assertEquals("Base", new JavaSourceStructure.ParentType("Base<T>", "Base", 0).rawTypeName());
  }

  private static JavaSourceStructure extract(String source) {
    return new JavaSourceStructureExtractor().extract(new JavaSourceSnapshot("repo", "commit", "blob", "Example.java", source),
        JavaAnalysisConfiguration.java21SyntaxOnly());
  }
}
