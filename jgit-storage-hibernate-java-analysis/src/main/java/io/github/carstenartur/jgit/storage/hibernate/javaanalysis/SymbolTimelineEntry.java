/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.List;
import java.util.Objects;

/** One occurrence of a logical Java symbol in an ordered commit history. */
public record SymbolTimelineEntry(
    int commitIndex,
    String commitId,
    JavaSymbolIndex symbol,
    List<SemanticChange> changesFromPrevious) {

  public SymbolTimelineEntry {
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(symbol, "symbol");
    changesFromPrevious = List.copyOf(Objects.requireNonNull(changesFromPrevious, "changesFromPrevious"));
  }
}
