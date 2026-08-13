/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/**
 * Dependency-free policy SPI used to bind an explicit application access context to Core.
 *
 * <p>Implementations must fail closed by throwing {@link RepositoryAccessDeniedException} when the
 * requested operation is not permitted. Other runtime failures also abort the storage operation.
 *
 * @param <C> immutable access-context type supplied by the optional security capability or consumer
 */
@FunctionalInterface
public interface RepositoryAccessPolicy<C> {

  /**
   * Require one repository operation for the explicit access context.
   *
   * @param accessContext authenticated, explicitly propagated context
   * @param request exact repository or ref operation
   * @throws RepositoryAccessDeniedException when access is denied
   */
  void require(C accessContext, RepositoryAccessRequest request);
}
