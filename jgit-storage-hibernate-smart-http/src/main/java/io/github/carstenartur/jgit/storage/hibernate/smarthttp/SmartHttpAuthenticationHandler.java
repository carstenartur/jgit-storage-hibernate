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
 * Authenticates the credential payload selected for exactly one configured route.
 *
 * <p>The supplied value is the Basic payload or bearer value without the authorization scheme. It
 * is intentionally passed only to the selected handler. Implementations must not retain, log, add to
 * exception messages or copy it into audit metadata.
 *
 * @param <C> immutable authenticated access-context type
 */
@FunctionalInterface
public interface SmartHttpAuthenticationHandler<C> {

  /**
   * Authenticate the selected credential and return one stable principal-bound context.
   *
   * @param request current servlet request
   * @param credential selected raw credential payload with bounded request lifetime
   * @return non-null immutable access context
   * @throws ServiceNotAuthorizedException for a generic authentication denial
   * @throws ServiceMayNotContinueException for a bounded protocol or infrastructure failure
   */
  C authenticate(HttpServletRequest request, String credential)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException;
}
