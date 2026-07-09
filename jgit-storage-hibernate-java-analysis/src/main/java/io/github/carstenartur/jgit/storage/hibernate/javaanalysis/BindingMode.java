/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Binding strategy requested for a Java analysis run. */
public enum BindingMode {
  /** Parse syntax only and do not ask JDT to resolve bindings. */
  DISABLED,

  /** Resolve bindings, but do not ask JDT to recover incomplete bindings. */
  REQUIRED,

  /** Resolve bindings and allow JDT to return recovered bindings when the context is incomplete. */
  RECOVERY
}
