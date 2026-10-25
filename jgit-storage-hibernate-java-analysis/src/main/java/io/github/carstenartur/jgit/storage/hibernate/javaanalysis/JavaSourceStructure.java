/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.List;

/** Immutable syntax-level declaration facets; no binding or persistence contract is implied. */
public record JavaSourceStructure(
    List<TypeDeclaration> types, List<ImportDeclaration> imports, int problemCount, int errorCount,
    String packageName, List<TypeUse> typeUses, List<FieldDeclaration> fields) {

  public JavaSourceStructure {
    types = List.copyOf(types);
    imports = List.copyOf(imports);
    packageName = java.util.Objects.requireNonNull(packageName, "packageName");
    typeUses = List.copyOf(typeUses);
    fields = List.copyOf(fields);
  }

  /** Retains the constructor descriptor for callers without field-declaration metadata. */
  public JavaSourceStructure(List<TypeDeclaration> types, List<ImportDeclaration> imports,
      int problemCount, int errorCount, String packageName, List<TypeUse> typeUses) {
    this(types, imports, problemCount, errorCount, packageName, typeUses, List.of());
  }

  /** Compatibility constructor for callers without package metadata; empty means unnamed/unknown. */
  public JavaSourceStructure(List<TypeDeclaration> types, List<ImportDeclaration> imports,
      int problemCount, int errorCount) {
    this(types, imports, problemCount, errorCount, "", List.of(), List.of());
  }

  /** Distinguishes declaration forms without changing the existing JavaSymbolKind.TYPE contract. */
  public enum TypeKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE }

  /** Declaration-level uses; local variables, casts, throws clauses and type arguments are excluded. */
  public enum TypeUseRole { FIELD, METHOD_PARAMETER, METHOD_RETURN, CATCH }

  /**
   * Rendered declaration type syntax, including primitive/void and whole array/union syntax. Raw type names
   * remove outer generic arguments only; they do not resolve imports or bindings. Extra dimensions
   * after a variable name and varargs punctuation are not part of the Type node. Owner coordinates
   * associate parameters/returns with their method, fields with their declaration and catches with
   * their clause. Encounter order keeps method parameters before its return, then body uses.
   */
  public record TypeUse(TypeUseRole role, String sourceName, String rawTypeName,
      int ownerStartPosition, int startPosition, int sourceLength) {}

  /** Named declaration with source coordinates and explicitly written parents. */
  public record TypeDeclaration(
      String name, TypeKind kind, int startPosition, int sourceLength, int startLine, int endLine,
      List<ParentType> extendedTypes, List<ParentType> implementedTypes) {
    public TypeDeclaration {
      extendedTypes = List.copyOf(extendedTypes);
      implementedTypes = List.copyOf(implementedTypes);
    }
  }

  /**
   * Parent syntax, its fully normalized name, outer-generics-only raw name, and source position.
   * The raw name follows {@link TypeUse#rawTypeName()}, retaining generic qualifiers and their annotations.
   */
  public record ParentType(String sourceName, String typeName, String rawTypeName, int startPosition) {
    /** Retains the old constructor descriptor; manually constructed parents default raw to normalized. */
    public ParentType(String sourceName, String typeName, int startPosition) {
      this(sourceName, typeName, typeName, startPosition);
    }
  }

  /**
   * Explicit field fragment or enum constant; record components and local variables are excluded.
   * All fragments/constants in a declaration group precede traversal of their initializer bodies.
   * For enum constants declarationStartPosition is the enclosing enum declaration's start;
   * otherwise it is the field declaration's start. The other coordinates describe the named fragment/constant.
   */
  public record FieldDeclaration(String name, int declarationStartPosition, int startPosition,
      int sourceLength, boolean enumConstant) {}

  /** Import name as written, without a trailing wildcard; flags preserve its meaning. */
  public record ImportDeclaration(String name, boolean isStatic, boolean onDemand) {}
}
