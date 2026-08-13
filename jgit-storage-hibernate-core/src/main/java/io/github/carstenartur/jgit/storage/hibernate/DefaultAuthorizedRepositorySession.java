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
import org.eclipse.jgit.lib.Repository;

/** Default principal-bound repository session. */
final class DefaultAuthorizedRepositorySession<C> implements AuthorizedRepositorySession<C> {

  private final RepositoryName repositoryName;
  private final C accessContext;
  private final RepositoryAccessPolicy<? super C> accessPolicy;
  private final HibernateGitStorage storage;

  DefaultAuthorizedRepositorySession(
      RepositoryName repositoryName,
      C accessContext,
      RepositoryAccessPolicy<? super C> accessPolicy,
      HibernateGitStorage storage) {
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
    this.accessContext = Objects.requireNonNull(accessContext, "accessContext");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.storage = Objects.requireNonNull(storage, "storage");
  }

  @Override
  public RepositoryName repositoryName() {
    return repositoryName;
  }

  @Override
  public C accessContext() {
    return accessContext;
  }

  @Override
  public Repository repository() {
    return storage.repository();
  }

  @Override
  public void require(RepositoryAccessRequest request) {
    Objects.requireNonNull(request, "request");
    if (!repositoryName.equals(request.repositoryName())) {
      throw new IllegalArgumentException(
          "request repository "
              + request.repositoryName()
              + " does not match session repository "
              + repositoryName);
    }
    accessPolicy.require(accessContext, request);
  }

  @Override
  public void close() {
    storage.close();
  }
}
