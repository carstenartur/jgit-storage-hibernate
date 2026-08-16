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
import io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * JGit repository resolver that authenticates explicitly and returns only principal-bound handles.
 *
 * <p>Both an ACL denial and a missing repository are exposed as the same JGit not-found result, so
 * authenticated callers without {@code DISCOVER} cannot distinguish repository existence.
 *
 * @param <C> access-context type
 */
public final class SecuredSmartHttpRepositoryResolver<C>
    implements RepositoryResolver<HttpServletRequest> {

  private final SecuredHibernateRepositoryFactory<C> repositoryFactory;
  private final SmartHttpAccessContextProvider<C> accessContextProvider;
  private final SmartHttpRepositoryNameMapper repositoryNameMapper;

  /** Create a resolver using the strict default name mapper. */
  public SecuredSmartHttpRepositoryResolver(
      SecuredHibernateRepositoryFactory<C> repositoryFactory,
      SmartHttpAccessContextProvider<C> accessContextProvider) {
    this(repositoryFactory, accessContextProvider, SmartHttpRepositoryNameMapper.strict());
  }

  /** Create a resolver using an explicit name mapper. */
  public SecuredSmartHttpRepositoryResolver(
      SecuredHibernateRepositoryFactory<C> repositoryFactory,
      SmartHttpAccessContextProvider<C> accessContextProvider,
      SmartHttpRepositoryNameMapper repositoryNameMapper) {
    this.repositoryFactory = Objects.requireNonNull(repositoryFactory, "repositoryFactory");
    this.accessContextProvider =
        Objects.requireNonNull(accessContextProvider, "accessContextProvider");
    this.repositoryNameMapper =
        Objects.requireNonNull(repositoryNameMapper, "repositoryNameMapper");
  }

  @Override
  public Repository open(HttpServletRequest request, String name)
      throws RepositoryNotFoundException,
          ServiceNotAuthorizedException,
          ServiceNotEnabledException,
          ServiceMayNotContinueException {
    Objects.requireNonNull(request, "request");
    SmartHttpRequestBindings.clear(request);
    RepositoryName repositoryName = repositoryNameMapper.map(name);
    C accessContext = accessContextProvider.require(request);
    if (accessContext == null) {
      throw new ServiceMayNotContinueException(
          "Authentication provider returned no access context",
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    AuthorizedRepositorySession<C> session = null;
    boolean handedOff = false;
    try {
      session = repositoryFactory.open(repositoryName, accessContext);
      Repository repository = session.repository();
      SmartHttpRequestBindings.bind(
          request, new SmartHttpRequestBinding<>(session, repository));
      handedOff = true;
      return repository;
    } catch (HibernateStorageException hidden) {
      throw new RepositoryNotFoundException(name, hidden);
    } catch (RuntimeException failure) {
      throw new ServiceMayNotContinueException(
          "Repository service unavailable",
          failure,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } finally {
      if (!handedOff && session != null) {
        session.close();
      }
    }
  }
}
