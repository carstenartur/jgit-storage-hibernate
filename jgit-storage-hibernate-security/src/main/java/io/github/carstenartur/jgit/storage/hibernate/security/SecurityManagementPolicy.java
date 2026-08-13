/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Application-supplied authorization boundary for credential and token lifecycle changes. */
@FunctionalInterface
public interface SecurityManagementPolicy {

  /**
   * Require one explicit credential-management operation.
   *
   * <p>Implementations must fail closed by throwing {@link SecurityManagementDeniedException}.
   * Other runtime failures also abort the management operation.
   *
   * @param request actor, subject and operation evidence
   */
  void require(SecurityManagementRequest request);
}
