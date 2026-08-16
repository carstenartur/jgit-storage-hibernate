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
import java.util.Objects;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;

final class SmartHttpRequestBindings {

  private static final String ATTRIBUTE =
      SmartHttpRequestBindings.class.getName() + ".AUTHORIZED_REPOSITORY";

  private SmartHttpRequestBindings() {}

  static void clear(HttpServletRequest request) {
    Objects.requireNonNull(request, "request").removeAttribute(ATTRIBUTE);
  }

  static <C> void bind(HttpServletRequest request, SmartHttpRequestBinding<C> binding) {
    Objects.requireNonNull(request, "request")
        .setAttribute(ATTRIBUTE, Objects.requireNonNull(binding, "binding"));
  }

  @SuppressWarnings("unchecked")
  static <C> SmartHttpRequestBinding<C> require(
      HttpServletRequest request, Repository repository)
      throws ServiceNotAuthorizedException {
    Object candidate = Objects.requireNonNull(request, "request").getAttribute(ATTRIBUTE);
    if (!(candidate instanceof SmartHttpRequestBinding<?> binding)
        || binding.repository() != Objects.requireNonNull(repository, "repository")) {
      throw new ServiceNotAuthorizedException();
    }
    return (SmartHttpRequestBinding<C>) binding;
  }
}
