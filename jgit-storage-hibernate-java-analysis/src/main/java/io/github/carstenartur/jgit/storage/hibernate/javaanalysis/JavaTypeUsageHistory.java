/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.List;
import java.util.Objects;

/**
 * Binding-aware usage history of one logical Java type across ordered repository commits.
 *
 * @param logicalId durable logical identity used by the symbol timeline
 * @param versions type declaration and usage sites in each version where the type exists
 */
public record JavaTypeUsageHistory(String logicalId, List<Version> versions) {

  public JavaTypeUsageHistory {
    Objects.requireNonNull(logicalId, "logicalId");
    versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
  }

  /**
   * Return the latest version in the history.
   *
   * @return latest version
   * @throws IllegalStateException if the history is empty
   */
  public Version latest() {
    if (versions.isEmpty()) {
      throw new IllegalStateException("usage history is empty");
    }
    return versions.getLast();
  }

  /**
   * One analyzed repository version.
   *
   * @param commitIndex position in the ordered analysis input
   * @param commitId Git commit ID
   * @param type type declaration in this version
   * @param usageSites incoming semantic relations that use the type
   */
  public record Version(
      int commitIndex,
      String commitId,
      JavaSymbolIndex type,
      List<UsageSite> usageSites) {

    public Version {
      Objects.requireNonNull(commitId, "commitId");
      Objects.requireNonNull(type, "type");
      usageSites = List.copyOf(Objects.requireNonNull(usageSites, "usageSites"));
    }
  }

  /**
   * One code location whose binding-aware semantic relation targets the queried type.
   *
   * @param relation semantic relation to the type
   * @param sourceSemanticKey stable key of the using declaration
   * @param sourceQualifiedName qualified name when the source declaration is available
   * @param sourceKind kind of the using declaration when available
   * @param path repository path containing the usage
   * @param line source line containing the usage
   * @param bindingStatus binding quality of the reference
   */
  public record UsageSite(
      JavaGraphEdgeKind relation,
      String sourceSemanticKey,
      String sourceQualifiedName,
      JavaSymbolKind sourceKind,
      String path,
      int line,
      BindingStatus bindingStatus) {

    public UsageSite {
      Objects.requireNonNull(relation, "relation");
      Objects.requireNonNull(sourceSemanticKey, "sourceSemanticKey");
      Objects.requireNonNull(bindingStatus, "bindingStatus");
    }
  }
}
