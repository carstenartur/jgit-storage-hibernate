/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessPolicy;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;
import java.util.function.Function;

/** Adapts the Security evaluator to Core's dependency-free repository access SPI. */
public final class SecurityRepositoryAccessPolicy
    implements RepositoryAccessPolicy<GitAccessContext> {

  private final Function<RepositoryName, SecurityAuthorizationEvaluator> evaluatorProvider;
  private final SecurityAccessAuditRecorder auditRecorder;

  /**
   * Create a policy using one immutable evaluator snapshot without persistent audit.
   *
   * @param evaluator evaluator snapshot
   */
  public SecurityRepositoryAccessPolicy(SecurityAuthorizationEvaluator evaluator) {
    this(evaluator, SecurityAccessAuditRecorder.NONE);
  }

  /**
   * Create a policy using one immutable evaluator snapshot and an explicit audit recorder.
   *
   * @param evaluator evaluator snapshot
   * @param auditRecorder authorization audit sink
   */
  public SecurityRepositoryAccessPolicy(
      SecurityAuthorizationEvaluator evaluator,
      SecurityAccessAuditRecorder auditRecorder) {
    this(
        ignored -> Objects.requireNonNull(evaluator, "evaluator"),
        auditRecorder);
  }

  /**
   * Create a policy resolving the current evaluator for every operation without persistent audit.
   *
   * @param evaluatorProvider provider keyed by immutable logical repository name
   */
  public SecurityRepositoryAccessPolicy(
      Function<RepositoryName, SecurityAuthorizationEvaluator> evaluatorProvider) {
    this(evaluatorProvider, SecurityAccessAuditRecorder.NONE);
  }

  /**
   * Create a policy resolving the current evaluator for every operation and auditing every outcome.
   *
   * @param evaluatorProvider provider keyed by immutable logical repository name
   * @param auditRecorder authorization audit sink
   */
  public SecurityRepositoryAccessPolicy(
      Function<RepositoryName, SecurityAuthorizationEvaluator> evaluatorProvider,
      SecurityAccessAuditRecorder auditRecorder) {
    this.evaluatorProvider =
        Objects.requireNonNull(evaluatorProvider, "evaluatorProvider");
    this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
  }

  @Override
  public void require(GitAccessContext context, RepositoryAccessRequest request) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");

    AuthorizationDecision decision;
    try {
      SecurityAuthorizationEvaluator evaluator =
          Objects.requireNonNull(
              evaluatorProvider.apply(request.repositoryName()),
              "evaluatorProvider result");
      decision =
          Objects.requireNonNull(
              evaluator.authorize(context, authorizationRequest(request)),
              "authorization decision");
    } catch (RuntimeException failure) {
      SecurityAuditSupport.fail(
          auditRecorder,
          SecurityAccessAuditRecord.failed(context, request, failure),
          failure);
      return;
    }

    SecurityAccessAuditRecord record =
        SecurityAccessAuditRecord.decision(context, request, decision);
    if (decision.allowed()) {
      SecurityAuditSupport.recordAllowed(auditRecorder, record);
      return;
    }

    RepositoryAccessDeniedException denied =
        new RepositoryAccessDeniedException(
            request,
            decision.reason().name(),
            decision.evidenceId(),
            decision.policyVersion());
    SecurityAuditSupport.deny(auditRecorder, record, denied);
  }

  private static RepositoryAuthorizationRequest authorizationRequest(
      RepositoryAccessRequest request) {
    GitRepositoryPermission permission = permission(request.operation());
    return request.refScoped()
        ? RepositoryAuthorizationRequest.ref(
            request.repositoryName(), permission, request.refName())
        : RepositoryAuthorizationRequest.repository(request.repositoryName(), permission);
  }

  private static GitRepositoryPermission permission(RepositoryAccessOperation operation) {
    return switch (operation) {
      case DISCOVER -> GitRepositoryPermission.DISCOVER;
      case READ -> GitRepositoryPermission.READ;
      case CREATE_REF -> GitRepositoryPermission.CREATE_REF;
      case UPDATE_REF -> GitRepositoryPermission.UPDATE_REF;
      case DELETE_REF -> GitRepositoryPermission.DELETE_REF;
      case FORCE_UPDATE -> GitRepositoryPermission.FORCE_UPDATE;
      case DELETE_REPOSITORY -> GitRepositoryPermission.ADMINISTER;
    };
  }
}
