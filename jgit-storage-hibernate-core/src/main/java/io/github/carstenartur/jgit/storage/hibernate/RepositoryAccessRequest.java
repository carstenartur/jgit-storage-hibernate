/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;

/** Immutable repository-level or ref-scoped access request evaluated by Core. */
public record RepositoryAccessRequest(
    RepositoryName repositoryName,
    RepositoryAccessOperation operation,
    String refName,
    ObjectId oldObjectId,
    ObjectId newObjectId) {

  /** Creates and validates one access request. */
  public RepositoryAccessRequest {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(operation, "operation");
    if (operation.refScoped()) {
      if (refName == null || refName.isBlank()) {
        throw new IllegalArgumentException("refName is required for " + operation);
      }
    } else if (refName != null || oldObjectId != null || newObjectId != null) {
      throw new IllegalArgumentException(
          "repository-level operation must not contain ref or object identifiers");
    }
    oldObjectId = oldObjectId != null ? oldObjectId.copy() : null;
    newObjectId = newObjectId != null ? newObjectId.copy() : null;
  }

  /**
   * Create a repository-level request.
   *
   * @param repositoryName logical repository
   * @param operation repository-level operation
   * @return validated request
   */
  public static RepositoryAccessRequest repository(
      RepositoryName repositoryName, RepositoryAccessOperation operation) {
    if (Objects.requireNonNull(operation, "operation").refScoped()) {
      throw new IllegalArgumentException(operation + " requires a refName");
    }
    return new RepositoryAccessRequest(repositoryName, operation, null, null, null);
  }

  /**
   * Create a ref-scoped request.
   *
   * @param repositoryName logical repository
   * @param operation ref mutation operation
   * @param refName exact affected ref
   * @param oldObjectId expected old object identifier, or {@code null} for a symbolic ref
   * @param newObjectId requested new object identifier, or {@code null} for a symbolic ref
   * @return validated request
   */
  public static RepositoryAccessRequest ref(
      RepositoryName repositoryName,
      RepositoryAccessOperation operation,
      String refName,
      ObjectId oldObjectId,
      ObjectId newObjectId) {
    if (!Objects.requireNonNull(operation, "operation").refScoped()) {
      throw new IllegalArgumentException(operation + " is not ref-scoped");
    }
    return new RepositoryAccessRequest(
        repositoryName, operation, refName, oldObjectId, newObjectId);
  }

  /**
   * Return whether this request identifies an exact ref mutation.
   *
   * @return {@code true} for a ref-scoped request
   */
  public boolean refScoped() {
    return operation.refScoped();
  }
}
