/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Java reference kind represented in the semantic index. */
public enum JavaReferenceKind {
  IMPORT,
  TYPE_REFERENCE,
  METHOD_INVOCATION,
  CONSTRUCTOR_INVOCATION,
  FIELD_ACCESS,
  ANNOTATION_USE
}
