/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.architecture.entity.ArchitectureDriftFindingIndex;
import io.github.carstenartur.jgit.storage.hibernate.architecture.entity.ArchitectureEvidenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.architecture.entity.ArchitectureRuleIndex;
import java.util.List;

/** Hibernate entity registration helper for architecture projections. */
public final class ArchitectureEntities {
  private ArchitectureEntities() {}
  public static List<Class<?>> annotatedClasses() {
    return List.of(ArchitectureRuleIndex.class, ArchitectureEvidenceIndex.class, ArchitectureDriftFindingIndex.class);
  }
}
