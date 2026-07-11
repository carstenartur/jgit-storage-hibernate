/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evidence collected for one semantic candidate. */
public record CandidateEvidence(
    String description,
    String beforeExample,
    String afterExample,
    List<String> negativeExamples) {

  public CandidateEvidence {
    Objects.requireNonNull(description, "description");
    negativeExamples = List.copyOf(negativeExamples == null ? List.of() : negativeExamples);
  }

  public static CandidateEvidence of(String description) {
    return new CandidateEvidence(description, null, null, List.of());
  }

  public static CandidateEvidence withExamples(String description, String before, String after) {
    return new CandidateEvidence(description, before, after, List.of());
  }

  public CandidateEvidence withNegativeExample(String negative) {
    List<String> updated = new ArrayList<>(negativeExamples);
    updated.add(negative);
    return new CandidateEvidence(description, beforeExample, afterExample, updated);
  }
}
