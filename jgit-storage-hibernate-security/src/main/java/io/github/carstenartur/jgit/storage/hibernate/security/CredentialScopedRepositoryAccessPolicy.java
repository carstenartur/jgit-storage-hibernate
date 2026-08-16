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
import java.util.Objects;

/**
 * Applies credential scopes before delegating to the authoritative repository ACL policy.
 *
 * <p>A password or external identity carries every permission to the ACL layer. An access token
 * can only carry its persisted scopes. Passing this policy therefore cannot grant a permission that
 * the repository grants and protected-ref rules would otherwise deny.
 */
public final class CredentialScopedRepositoryAccessPolicy
    implements RepositoryAccessPolicy<AuthenticatedGitAccess> {

  private final RepositoryAccessPolicy<GitAccessContext> repositoryPolicy;
  private final SecurityAccessAuditRecorder auditRecorder;

  /** Create a credential-scope boundary without an additional scope-denial audit sink. */
  public CredentialScopedRepositoryAccessPolicy(
      RepositoryAccessPolicy<GitAccessContext> repositoryPolicy) {
    this(repositoryPolicy, SecurityAccessAuditRecorder.NONE);
  }

  /** Create a credential-scope boundary that audits denials before repository ACL evaluation. */
  public CredentialScopedRepositoryAccessPolicy(
      RepositoryAccessPolicy<GitAccessContext> repositoryPolicy,
      SecurityAccessAuditRecorder auditRecorder) {
    this.repositoryPolicy = Objects.requireNonNull(repositoryPolicy, "repositoryPolicy");
    this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
  }

  @Override
  public void require(AuthenticatedGitAccess access, RepositoryAccessRequest request) {
    Objects.requireNonNull(access, "access");
    Objects.requireNonNull(request, "request");
    GitRepositoryPermission permission = permission(request.operation());
    if (!access.carries(permission)) {
      RepositoryAccessDeniedException denied =
          new RepositoryAccessDeniedException(
              request,
              SecurityAuthenticationReason.CREDENTIAL_SCOPE_DENY.name(),
              access.credentialId(),
              access.credentialVersion());
      SecurityAuditSupport.deny(
          auditRecorder, SecurityAccessAuditRecord.denied(access.context(), denied), denied);
    }
    repositoryPolicy.require(access.context(), request);
  }

  private static GitRepositoryPermission permission(RepositoryAccessOperation operation) {
    return switch (Objects.requireNonNull(operation, "operation")) {
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
