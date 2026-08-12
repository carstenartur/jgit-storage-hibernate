/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;

/** One repository-level or ref-scoped authorization request. */
public record RepositoryAuthorizationRequest(
    RepositoryName repositoryName, GitRepositoryPermission permission, String refName) {

  /** Creates a repository-level request. */
  public static RepositoryAuthorizationRequest repository(
      RepositoryName repositoryName, GitRepositoryPermission permission) {
    return new RepositoryAuthorizationRequest(repositoryName, permission, null);
  }

  /** Creates a ref-scoped request. */
  public static RepositoryAuthorizationRequest ref(
      RepositoryName repositoryName, GitRepositoryPermission permission, String refName) {
    return new RepositoryAuthorizationRequest(repositoryName, permission, refName);
  }

  /** Creates a validated request. */
  public RepositoryAuthorizationRequest {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(permission, "permission");
    if (refName != null) {
      if (!refName.startsWith("refs/") || refName.length() > 1024) {
        throw new IllegalArgumentException(
            "refName must start with refs/ and contain at most 1024 characters");
      }
      for (int index = 0; index < refName.length(); index++) {
        if (Character.isISOControl(refName.charAt(index))) {
          throw new IllegalArgumentException("refName must not contain control characters");
        }
      }
    }
  }

  /** Returns whether this request is scoped to one Git ref. */
  public boolean refScoped() {
    return refName != null;
  }
}
