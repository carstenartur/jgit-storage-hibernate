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
import java.util.Objects;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * Creates an atomic JGit receive-pack after current read access and explicit coarse admission.
 *
 * @param <C> access-context type
 */
public final class SecuredSmartHttpReceivePackFactory<C>
    implements ReceivePackFactory<HttpServletRequest> {

  private final SmartHttpReceiveAdmission<? super C> receiveAdmission;
  private final SmartHttpPostReceiveHandler<? super C> postReceiveHandler;

  /** Disable receive-pack until an explicit coarse admission policy is supplied. */
  public SecuredSmartHttpReceivePackFactory() {
    this(SmartHttpReceiveAdmission.disabled(), SmartHttpPostReceiveHandler.none());
  }

  /** Create a factory with an application-owned coarse receive-pack admission check. */
  public SecuredSmartHttpReceivePackFactory(
      SmartHttpReceiveAdmission<? super C> receiveAdmission) {
    this(receiveAdmission, SmartHttpPostReceiveHandler.none());
  }

  /**
   * Create a factory with explicit receive admission and post-receive projection scheduling.
   *
   * @param receiveAdmission coarse check before JGit accepts pack data
   * @param postReceiveHandler bounded callback after command results are known
   */
  public SecuredSmartHttpReceivePackFactory(
      SmartHttpReceiveAdmission<? super C> receiveAdmission,
      SmartHttpPostReceiveHandler<? super C> postReceiveHandler) {
    this.receiveAdmission = Objects.requireNonNull(receiveAdmission, "receiveAdmission");
    this.postReceiveHandler = Objects.requireNonNull(postReceiveHandler, "postReceiveHandler");
  }

  @Override
  public ReceivePack create(HttpServletRequest request, Repository repository)
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
    receiveAdmission.require(
        request, binding.session().repositoryName(), binding.session().accessContext());

    ReceivePack receivePack = new ReceivePack(repository);
    receivePack.setAtomic(true);
    receivePack.setPostReceiveHook(
        (completedPack, commands) ->
            postReceiveHandler.onPostReceive(
                request,
                binding.session().repositoryName(),
                binding.session().accessContext(),
                completedPack,
                commands));
    return receivePack;
  }
}
