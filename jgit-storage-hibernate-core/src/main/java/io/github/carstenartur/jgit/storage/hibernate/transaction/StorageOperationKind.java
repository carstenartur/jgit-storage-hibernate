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
  REPOSITORY_INITIALIZATION,
  PACK_METADATA_READ,
  PACK_FILE_READ,
  PACK_EXTENSION_WRITE,
  PACK_PUBLICATION,
  PACK_ROLLBACK,
  PACK_MAINTENANCE,
  REF_PUBLICATION,
  REFLOG_READ,
  REFLOG_WRITE,
  OTHER
}
