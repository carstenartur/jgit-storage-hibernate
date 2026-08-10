/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Last authoritative stage reached by a logical repository transfer. */
public enum RepositoryTransferStage {
  /** Participant preflight runs before either repository is opened or created. */
  PRE_FLIGHT,

  /** Exact source refs are being resolved into the immutable operation snapshot. */
  SOURCE_SNAPSHOT,

  /** The target repository is being opened or created and its refs captured. */
  TARGET_PRECONDITION,

  /** Reachable canonical Git objects are being streamed and made durable in the target. */
  OBJECT_TRANSFER,

  /** The copied target graph is being traversed before any ref becomes visible. */
  CONNECTIVITY_VERIFICATION,

  /** Fast-forward, compare-and-set and force policy is being validated. */
  REF_POLICY_VALIDATION,

  /** Participants run and the atomic target-ref batch is published. */
  REF_PUBLICATION,

  /** Durable refs exist and completion participants are being notified. */
  COMPLETION_CALLBACK
}
