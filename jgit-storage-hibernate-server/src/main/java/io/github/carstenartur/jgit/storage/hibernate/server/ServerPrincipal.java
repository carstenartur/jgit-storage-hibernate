/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import java.security.Principal;
import java.util.Objects;

/** Immutable administrator identity propagated from HTTP authentication into Core. */
public record ServerPrincipal(String name) implements Principal {

  public ServerPrincipal {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("principal name must not be blank");
    }
  }

  @Override
  public String getName() {
    return name;
  }
}
