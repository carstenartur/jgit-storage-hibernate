/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import java.util.List;
import java.util.Objects;

/** Complete drift evaluation result for one code/architecture commit pair. */
public record ArchitectureDriftReport(
    ArchitectureSnapshot architecture,
    ArchitectureCodeMapping mapping,
    List<ArchitectureDriftFinding> findings) {
  public ArchitectureDriftReport {
    Objects.requireNonNull(architecture, "architecture");
    Objects.requireNonNull(mapping, "mapping");
    findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
  }

  public boolean compliant() { return findings.isEmpty(); }
  public long count(ArchitectureDriftKind kind) {
    return findings.stream().filter(finding -> finding.kind() == kind).count();
  }
}
