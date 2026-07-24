/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.schema;

/** Thrown when a pre-library core schema cannot be adopted without risking data loss. */
public final class LegacyCoreSchemaAdoptionException extends IllegalStateException {

  /**
   * Create an adoption validation exception.
   *
   * @param message actionable validation failure
   */
  public LegacyCoreSchemaAdoptionException(String message) {
    super(message);
  }

  /**
   * Create an adoption validation exception.
   *
   * @param message actionable validation failure
   * @param cause underlying database failure
   */
  public LegacyCoreSchemaAdoptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
