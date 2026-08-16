/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

/** Revalidates repository read access before creating JGit's upload-pack service. */
public final class SecuredSmartHttpUploadPackFactory<C>
    implements UploadPackFactory<HttpServletRequest> {

  @Override
  public UploadPack create(HttpServletRequest request, Repository repository)
      throws ServiceNotEnabledException, ServiceNotAuthorizedException {
    SmartHttpRequestBinding<C> binding =
        SmartHttpRequestBindings.require(request, repository);
    try {
      binding
          .session()
          .require(
              RepositoryAccessRequest.repository(
                  binding.session().repositoryName(), RepositoryAccessOperation.READ));
    } catch (RepositoryAccessDeniedException denied) {
      // The bounded audit record carries the internal reason. Do not disclose revocation or ACL
      // details through the Git protocol response.
      throw new ServiceNotAuthorizedException();
    }
    return new UploadPack(repository);
  }
}
