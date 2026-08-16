/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Controls whether policy rows outside one managed source namespace may coexist. */
public enum RepositoryPolicyOwnershipMode {
  /** Reconcile only the selected source namespace and preserve every other policy row. */
  NAMESPACE_ONLY,

  /** Reject reconciliation while any grant or ref rule outside the source namespace exists. */
  EXCLUSIVE_REPOSITORY
}
