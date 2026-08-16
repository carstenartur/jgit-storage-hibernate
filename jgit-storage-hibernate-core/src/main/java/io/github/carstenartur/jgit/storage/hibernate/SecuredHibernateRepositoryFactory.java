/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/**
 * Principal-bound facade that keeps the raw {@link HibernateRepositoryFactory} out of untrusted
 * application code.
 *
 * <p>Opening requires both {@link RepositoryAccessOperation#DISCOVER} and {@link
 * RepositoryAccessOperation#READ} before repository existence is inspected. The returned JGit
 * repository carries the same bound policy into single and batch ref updates. Repository deletion
 * requires its own {@link RepositoryAccessOperation#DELETE_REPOSITORY} check and is rechecked after
 * the repository row lock is acquired.
 *
 * <p>Repository creation and transfer deliberately remain privileged infrastructure operations in
 * this phase.
 *
 * @param <C> immutable access-context type
 */
public final class SecuredHibernateRepositoryFactory<C> {

  private final DefaultHibernateRepositoryFactory delegate;
  private final RepositoryAccessPolicy<? super C> accessPolicy;

  /**
   * Create a secured factory without optional deletion participants.
   *
   * @param sessionFactory Hibernate session factory
   * @param accessPolicy fail-closed access policy
   */
  public SecuredHibernateRepositoryFactory(
      SessionFactory sessionFactory, RepositoryAccessPolicy<? super C> accessPolicy) {
    this(sessionFactory, List.of(), accessPolicy);
  }

  /**
   * Create a secured factory with transactional projection-deletion participants.
   *
   * @param sessionFactory Hibernate session factory
   * @param deletionParticipants optional module-owned deletion participants
   * @param accessPolicy fail-closed access policy
   */
  public SecuredHibernateRepositoryFactory(
      SessionFactory sessionFactory,
      Collection<? extends RepositoryDeletionParticipant> deletionParticipants,
      RepositoryAccessPolicy<? super C> accessPolicy) {
    this.delegate =
        new DefaultHibernateRepositoryFactory(
            Objects.requireNonNull(sessionFactory, "sessionFactory"), deletionParticipants);
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
  }

  /**
   * Open an existing repository for one explicit access context.
   *
   * @param repositoryName logical repository
   * @param accessContext authenticated context
   * @return principal-bound repository session
   * @throws RepositoryDoesNotExistException when authorization succeeded but the repository is absent
   */
  public AuthorizedRepositorySession<C> open(
      RepositoryName repositoryName, C accessContext) {
    RepositoryName name = Objects.requireNonNull(repositoryName, "repositoryName");
    C context = Objects.requireNonNull(accessContext, "accessContext");
    Consumer<RepositoryAccessRequest> guard = request -> accessPolicy.require(context, request);
    HibernateGitStorage storage = delegate.openExisting(name, guard);
    return new DefaultAuthorizedRepositorySession<>(name, context, accessPolicy, storage);
  }

  /**
   * Delete one repository after an explicit precheck and a final lock-bound recheck.
   *
   * @param repositoryName logical repository
   * @param accessContext authenticated context
   * @return deleted row counts
   */
  public RepositoryDeletionResult deleteRepository(
      RepositoryName repositoryName, C accessContext) {
    RepositoryName name = Objects.requireNonNull(repositoryName, "repositoryName");
    C context = Objects.requireNonNull(accessContext, "accessContext");
    Consumer<RepositoryAccessRequest> guard = request -> accessPolicy.require(context, request);
    return delegate.deleteRepository(name, guard);
  }
}
