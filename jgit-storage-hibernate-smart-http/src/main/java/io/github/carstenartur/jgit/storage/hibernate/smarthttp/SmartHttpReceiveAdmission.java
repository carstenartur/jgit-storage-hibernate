/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * Optional coarse admission check performed before JGit accepts a receive-pack request body.
 *
 * <p>This callback is not the authority for exact ref commands. Core rechecks every command at the
 * atomic publication boundary even after this callback succeeds.
 *
 * @param <C> bound access-context type
 */
@FunctionalInterface
public interface SmartHttpReceiveAdmission<C> {

  /**
   * Require coarse permission to enter receive-pack.
   *
   * @param request current servlet request
   * @param repositoryName logical repository
   * @param accessContext authenticated access context
   * @throws ServiceNotAuthorizedException when this identity may not receive-pack
   * @throws ServiceNotEnabledException when receive-pack is disabled for the repository
   */
  void require(
      HttpServletRequest request, RepositoryName repositoryName, C accessContext)
      throws ServiceNotAuthorizedException, ServiceNotEnabledException;

  /**
   * Admit an already authenticated and readable repository to receive-pack.
   *
   * <p>Exact ref authorization remains mandatory and is enforced by Core.
   *
   * @param <C> access-context type
   * @return no-op coarse admission
   */
  static <C> SmartHttpReceiveAdmission<C> allowAuthenticatedRequests() {
    return (request, repositoryName, accessContext) -> {};
  }
}
