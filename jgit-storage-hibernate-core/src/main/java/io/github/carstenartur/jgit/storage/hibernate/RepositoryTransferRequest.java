/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable transfer plan resolved against one source-ref snapshot.
 *
 * @param source source logical repository
 * @param target target logical repository
 * @param refs exact source-to-target ref mappings
 * @param mode transfer mode
 * @param targetRefPolicy target ref publication policy
 * @param verifyConnectivity whether to traverse the copied target graph before publishing refs
 */
public record RepositoryTransferRequest(
    RepositoryName source,
    RepositoryName target,
    List<RefTransferSpec> refs,
    RepositoryTransferMode mode,
    TargetRefPolicy targetRefPolicy,
    boolean verifyConnectivity) {

  /** Validate and defensively copy a transfer plan. */
  public RepositoryTransferRequest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(refs, "refs");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(targetRefPolicy, "targetRefPolicy");
    refs = List.copyOf(refs);
    if (source.equals(target)) {
      throw new IllegalArgumentException("source and target repositories must differ");
    }
    if (refs.isEmpty()) {
      throw new IllegalArgumentException("at least one ref transfer is required");
    }
    Set<String> targetRefs = new HashSet<>();
    for (RefTransferSpec ref : refs) {
      if (!targetRefs.add(ref.targetRef())) {
        throw new IllegalArgumentException("duplicate target ref: " + ref.targetRef());
      }
    }
  }

  /**
   * Create the safe default plan for provisioning an empty target.
   *
   * @param source source logical repository
   * @param target target logical repository
   * @param refs exact source-to-target ref mappings
   * @return create-only initial-clone request with connectivity verification enabled
   */
  public static RepositoryTransferRequest initialClone(
      RepositoryName source, RepositoryName target, List<RefTransferSpec> refs) {
    return new RepositoryTransferRequest(
        source,
        target,
        refs,
        RepositoryTransferMode.INITIAL_CLONE,
        TargetRefPolicy.CREATE_ONLY,
        true);
  }
}
