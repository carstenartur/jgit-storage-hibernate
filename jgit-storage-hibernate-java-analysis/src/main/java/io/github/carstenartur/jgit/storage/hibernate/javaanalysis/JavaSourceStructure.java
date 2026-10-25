/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.List;

/** Immutable syntax-level declaration facets; no binding or persistence contract is implied. */
public record JavaSourceStructure(
    List<TypeDeclaration> types, List<ImportDeclaration> imports, int problemCount, int errorCount) {

  public JavaSourceStructure {
    types = List.copyOf(types);
    imports = List.copyOf(imports);
  }

  /** Distinguishes declaration forms without changing the existing JavaSymbolKind.TYPE contract. */
  public enum TypeKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE }

  /** Named declaration with source coordinates and explicitly written parents. */
  public record TypeDeclaration(
      String name, TypeKind kind, int startPosition, int sourceLength, int startLine, int endLine,
      List<ParentType> extendedTypes, List<ParentType> implementedTypes) {
    public TypeDeclaration {
      extendedTypes = List.copyOf(extendedTypes);
      implementedTypes = List.copyOf(implementedTypes);
    }
  }

  /** Parent syntax, its name without generic arguments/type annotations, and source position. */
  public record ParentType(String sourceName, String typeName, int startPosition) {}

  /** Import name as written, without a trailing wildcard; flags preserve its meaning. */
  public record ImportDeclaration(String name, boolean isStatic, boolean onDemand) {}
}
