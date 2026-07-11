/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate;

/** SPI for validating semantic candidates before promotion. */
public interface CandidateValidator {

  ValidationResult validate(SemanticCandidate candidate);

  static ValidationResult pass() {
    return new ValidationResult(true, "");
  }

  static ValidationResult fail(String message) {
    return new ValidationResult(false, message);
  }

  record ValidationResult(boolean passed, String message) {}
}
