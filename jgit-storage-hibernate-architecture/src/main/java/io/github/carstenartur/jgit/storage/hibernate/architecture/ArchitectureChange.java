/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.Objects;

/** One semantic DSL change identified through stable IDs. */
public record ArchitectureChange(
    ArchitectureChangeKind kind,
    String subjectId,
    Object before,
    Object after) {
  public ArchitectureChange {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(subjectId, "subjectId");
  }
}
