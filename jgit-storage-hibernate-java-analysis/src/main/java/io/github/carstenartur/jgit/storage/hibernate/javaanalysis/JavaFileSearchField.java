/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Searchable source facets; metadata filters are separate from analyzed text. */
public enum JavaFileSearchField {
  DECLARED_TYPES(Analysis.QUALIFIED),
  QUALIFIED_NAMES(Analysis.QUALIFIED),
  SIMPLE_CLASS_NAME(Analysis.IDENTIFIER),
  METHODS(Analysis.IDENTIFIER),
  FIELDS(Analysis.IDENTIFIER),
  EXTENDED_TYPES(Analysis.QUALIFIED),
  IMPLEMENTED_TYPES(Analysis.QUALIFIED),
  IMPORTS(Analysis.QUALIFIED),
  ANNOTATIONS(Analysis.QUALIFIED),
  REFERENCED_TYPES(Analysis.QUALIFIED),
  DOCUMENTATION(Analysis.TEXT),
  STRING_LITERALS(Analysis.TEXT),
  METHOD_SIGNATURES(Analysis.IDENTIFIER);

  enum Analysis { QUALIFIED, IDENTIFIER, TEXT }

  final Analysis analysis;

  JavaFileSearchField(Analysis analysis) {
    this.analysis = analysis;
  }
}
