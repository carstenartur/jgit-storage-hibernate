/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * Application callback invoked after JGit has finished an authenticated receive-pack operation.
 *
 * <p>The callback runs after command results are known. It is intended for bounded follow-up work
 * such as scheduling a rebuildable Search projection; authoritative Git publication and exact ref
 * authorization have already completed inside Core. Implementations must not reinterpret successful
 * commands as an alternative commit boundary and should hand expensive work to a bounded executor.
 *
 * @param <C> immutable access-context type
 */
@FunctionalInterface
public interface SmartHttpPostReceiveHandler<C> {

  /**
   * Observe one completed receive-pack operation.
   *
   * @param request current HTTP request
   * @param repositoryName logical repository
   * @param accessContext authenticated access context
   * @param receivePack completed JGit receive-pack
   * @param commands commands with their final results
   */
  void onPostReceive(
      HttpServletRequest request,
      RepositoryName repositoryName,
      C accessContext,
      ReceivePack receivePack,
      Collection<ReceiveCommand> commands);

  /** Return a no-op handler for applications that need no follow-up projection work. */
  static <C> SmartHttpPostReceiveHandler<C> none() {
    return (request, repositoryName, accessContext, receivePack, commands) -> {};
  }
}
