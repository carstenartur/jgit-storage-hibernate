/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.List;
import java.util.Objects;

/** Parsed snapshot plus non-fatal DSL diagnostics. */
public record ArchitectureDslParseResult(
    ArchitectureSnapshot snapshot,
    List<String> diagnostics) {
  public ArchitectureDslParseResult {
    Objects.requireNonNull(snapshot, "snapshot");
    diagnostics = List.copyOf(Objects.requireNonNullElse(diagnostics, List.of()));
  }

  public boolean valid() { return diagnostics.isEmpty(); }
}
