/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Resolution quality for a persisted Java symbol or reference. */
public enum BindingStatus {
  /** Binding resolution was not requested for this analysis run. */
  NONE,

  /** Binding was requested, but this individual symbol or reference could not be resolved. */
  PARTIAL,

  /** JDT returned a recovered binding. */
  RECOVERED,

  /** JDT returned a non-recovered binding. */
  FULL,

  /** Binding resolution failed before symbols or references could be analyzed reliably. */
  FAILED
}
