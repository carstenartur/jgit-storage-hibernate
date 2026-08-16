/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import java.util.Objects;
import org.eclipse.jgit.http.server.GitServlet;

/** Programmatic, framework-neutral wiring for the secured JGit Smart HTTP pipeline. */
public final class SecuredSmartHttp {

  private SecuredSmartHttp() {}

  /**
   * Create a fetch-only servlet with strict repository names and disabled receive-pack.
   *
   * <p>Push must be enabled through the overload that supplies an explicit {@link
   * SmartHttpReceiveAdmission}. This prevents a read-only deployment from accepting pack data merely
   * because exact Core ref authorization would reject publication later.
   *
   * @param repositoryFactory principal-bound repository factory
   * @param accessContextProvider request authentication boundary
   * @param <C> access-context type
   * @return configured servlet; the application still owns container registration and TLS
   */
  public static <C> GitServlet servlet(
      SecuredHibernateRepositoryFactory<C> repositoryFactory,
      SmartHttpAccessContextProvider<C> accessContextProvider) {
    return servlet(
        repositoryFactory,
        accessContextProvider,
        SmartHttpRepositoryNameMapper.strict(),
        SmartHttpReceiveAdmission.disabled());
  }

  /** Create a servlet with explicit name mapping and coarse receive admission. */
  public static <C> GitServlet servlet(
      SecuredHibernateRepositoryFactory<C> repositoryFactory,
      SmartHttpAccessContextProvider<C> accessContextProvider,
      SmartHttpRepositoryNameMapper repositoryNameMapper,
      SmartHttpReceiveAdmission<? super C> receiveAdmission) {
    GitServlet servlet = new GitServlet();
    configure(
        servlet,
        repositoryFactory,
        accessContextProvider,
        repositoryNameMapper,
        receiveAdmission);
    return servlet;
  }

  /** Configure an application-created JGit servlet before container initialization. */
  public static <C> void configure(
      GitServlet servlet,
      SecuredHibernateRepositoryFactory<C> repositoryFactory,
      SmartHttpAccessContextProvider<C> accessContextProvider,
      SmartHttpRepositoryNameMapper repositoryNameMapper,
      SmartHttpReceiveAdmission<? super C> receiveAdmission) {
    GitServlet target = Objects.requireNonNull(servlet, "servlet");
    target.setRepositoryResolver(
        new SecuredSmartHttpRepositoryResolver<>(
            repositoryFactory, accessContextProvider, repositoryNameMapper));
    target.setUploadPackFactory(new SecuredSmartHttpUploadPackFactory<>());
    target.setReceivePackFactory(new SecuredSmartHttpReceivePackFactory<>(receiveAdmission));
    target.setAsIsFileService(null);
  }
}
