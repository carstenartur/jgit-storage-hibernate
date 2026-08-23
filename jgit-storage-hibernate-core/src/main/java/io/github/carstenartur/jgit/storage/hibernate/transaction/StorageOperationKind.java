/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/** Stable categories for opt-in repository transaction and lock diagnostics. */
public enum StorageOperationKind {
  /** Creation or verification of repository coordination state. */
  REPOSITORY_INITIALIZATION,

  /** Reconstruction of committed DFS pack descriptions. */
  PACK_METADATA_READ,

  /** Opening one committed pack extension for reading. */
  PACK_FILE_READ,

  /** Persistence or lease renewal of unpublished pack extensions. */
  PACK_EXTENSION_WRITE,

  /** Atomic publication of a complete expected logical-pack extension set. */
  PACK_PUBLICATION,

  /** Best-effort removal of local or durable unpublished pack state after failure. */
  PACK_ROLLBACK,

  /** Cleanup or other explicit maintenance of pack persistence state. */
  PACK_MAINTENANCE,

  /** Locked ref/reftable publication, including nested reflog work. */
  REF_PUBLICATION,

  /** Standalone reflog retrieval. */
  REFLOG_READ,

  /** Standalone reflog persistence outside an owning ref-publication transaction. */
  REFLOG_WRITE,

  /** Atomic repository-locked append of idempotent queryable reflog projection records. */
  REFLOG_BATCH_WRITE,

  /** Explicit application work or an internal diagnostic call site not yet classified. */
  OTHER
}
