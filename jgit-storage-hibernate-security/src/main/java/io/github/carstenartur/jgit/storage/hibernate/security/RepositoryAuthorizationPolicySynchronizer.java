/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Framework-neutral desired-state boundary for application-managed Git authorization policy. */
@FunctionalInterface
public interface RepositoryAuthorizationPolicySynchronizer {

  /**
   * Reconcile one complete desired snapshot.
   *
   * @param desired complete desired state for one repository/source namespace
   * @param expectedCurrentPolicyVersion zero when no policy is expected, otherwise the exact
   *     currently active external policy version
   * @param actor stable authenticated actor authorizing the application-side policy projection
   * @param operationId stable host operation/idempotency evidence
   * @return bounded apply/no-op/stale/conflict evidence
   */
  RepositoryAuthorizationPolicySyncResult synchronize(
      RepositoryAuthorizationPolicySnapshot desired,
      long expectedCurrentPolicyVersion,
      GitAccessContext actor,
      String operationId);
}
