/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/**
 * Optional framework-neutral participant for authorization, auditing and derived-state lifecycle.
 *
 * <p>Preflight and pre-publication methods may throw a runtime exception to veto a transfer. A
 * pre-publication veto occurs after canonical target objects may have become durable but before any
 * requested target ref is changed. Completion is invoked only after durable atomic ref publication.
 * Failure notifications are best effort and can be used to mark projections stale or schedule
 * cleanup; their own exceptions are suppressed on the primary failure.
 */
public interface RepositoryTransferParticipant {

  /**
   * Authorize or validate a request before either repository is opened or the target is created.
   *
   * @param request immutable transfer plan
   */
  default void beforeTransfer(RepositoryTransferRequest request) {}

  /**
   * Recheck exact ref mutations immediately before the atomic publication boundary.
   *
   * @param publication durable-object and exact old/new ref evidence
   */
  default void beforeRefPublication(RepositoryTransferPublication publication) {}

  /**
   * Observe a fully committed transfer.
   *
   * <p>Throwing here cannot roll back Git objects or refs. The caller receives a
   * {@link RepositoryTransferCompletionException} containing the committed result and may safely
   * retry; a successful retry is normally a no-op and replays completion participants.
   *
   * @param result committed transfer evidence
   */
  default void afterTransfer(RepositoryTransferResult result) {}

  /**
   * Observe a failed transfer. Exceptions thrown by this callback are suppressed on the primary
   * failure and never turn a rejected operation into a successful one.
   *
   * @param failure failure stage and captured source evidence
   */
  default void transferFailed(RepositoryTransferFailure failure) {}
}
