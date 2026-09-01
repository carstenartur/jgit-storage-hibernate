/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.util.List;

/** Validates optional singleton selections for partitioned benchmark matrices. */
final class PerformanceInvestigationParameters {

  private PerformanceInvestigationParameters() {}

  static String[] select(
      String propertyName,
      String configuredValue,
      String[] defaultValues,
      String... allowedValues) {
    if (configuredValue == null || configuredValue.isBlank()) {
      return defaultValues.clone();
    }
    String value = configuredValue.trim();
    if (!List.of(allowedValues).contains(value)) {
      throw new IllegalArgumentException(
          propertyName
              + " must be one of "
              + String.join(", ", allowedValues)
              + " but was "
              + value);
    }
    return new String[] {value};
  }
}
