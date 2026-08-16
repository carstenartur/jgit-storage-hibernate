/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import io.github.carstenartur.jgit.storage.hibernate.AuthorizedRepositorySession;
import java.util.Objects;
import org.eclipse.jgit.lib.Repository;

record SmartHttpRequestBinding<C>(
    AuthorizedRepositorySession<C> session, Repository repository) {

  SmartHttpRequestBinding {
    session = Objects.requireNonNull(session, "session");
    repository = Objects.requireNonNull(repository, "repository");
    if (session.repository() != repository) {
      throw new IllegalArgumentException("session and repository must identify the same handle");
    }
  }
}
