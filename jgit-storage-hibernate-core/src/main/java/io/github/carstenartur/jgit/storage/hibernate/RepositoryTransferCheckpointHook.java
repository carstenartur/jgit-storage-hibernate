/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.io.IOException;
import org.eclipse.jgit.lib.Repository;

/** Internal deterministic checkpoints for transfer failure and concurrency contracts. */
@FunctionalInterface
interface RepositoryTransferCheckpointHook {

  RepositoryTransferCheckpointHook NONE = (phase, source, target, request) -> {};

  void reached(
      Phase phase,
      Repository source,
      Repository target,
      RepositoryTransferRequest request)
      throws IOException;

  enum Phase {
    TARGET_REFS_PREPARED,
    OBJECTS_FLUSHED,
    PRECONDITIONS_VALIDATED,
    BEFORE_REF_PUBLICATION
  }
}
