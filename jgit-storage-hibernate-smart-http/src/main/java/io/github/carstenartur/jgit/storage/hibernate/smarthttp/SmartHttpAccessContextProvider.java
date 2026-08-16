/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;

/**
 * Converts one HTTP request into the immutable access context bound to a secured repository.
 *
 * @param <C> application or Security access-context type
 */
@FunctionalInterface
public interface SmartHttpAccessContextProvider<C> {

  /**
   * Require and return the authenticated context for one request.
   *
   * @param request current servlet request
   * @return non-null immutable access context
   * @throws ServiceNotAuthorizedException when authentication is missing or invalid
   * @throws ServiceMayNotContinueException when authentication infrastructure fails
   */
  C require(HttpServletRequest request)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException;
}
