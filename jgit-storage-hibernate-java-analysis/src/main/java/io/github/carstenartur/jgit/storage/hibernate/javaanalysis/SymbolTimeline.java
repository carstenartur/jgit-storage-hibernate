/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.List;
import java.util.Objects;

/** Ordered history of one logical symbol across commits. */
public record SymbolTimeline(String logicalId, List<SymbolTimelineEntry> entries) {
  public SymbolTimeline {
    Objects.requireNonNull(logicalId, "logicalId");
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries must not be empty");
    }
  }

  public SymbolTimelineEntry first() { return entries.getFirst(); }
  public SymbolTimelineEntry latest() { return entries.getLast(); }
}
