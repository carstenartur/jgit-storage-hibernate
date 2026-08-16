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
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessPolicy;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Revalidates persisted access-token state before applying credential scopes and repository ACLs.
 *
 * <p>The check runs once for each sensitive Core operation: repository discovery/read when opening a
 * session, every ref publication and repository deletion. It is never invoked per Git object, pack
 * chunk or advertised ref. Password and external identities pass directly to the credential-scope and
 * repository policies because their surrounding application session owns their revocation lifetime.
 */
public final class HibernateCredentialScopedRepositoryAccessPolicy
    implements RepositoryAccessPolicy<AuthenticatedGitAccess> {

  private final SessionFactory sessionFactory;
  private final CredentialScopedRepositoryAccessPolicy credentialPolicy;
  private final SecurityAccessAuditRecorder auditRecorder;
  private final Clock clock;

  /** Create a database-backed credential boundary without a separate audit sink. */
  public HibernateCredentialScopedRepositoryAccessPolicy(
      SessionFactory sessionFactory,
      RepositoryAccessPolicy<GitAccessContext> repositoryPolicy) {
    this(
        sessionFactory,
        repositoryPolicy,
        SecurityAccessAuditRecorder.NONE,
        Clock.systemUTC());
  }

  /** Create a database-backed credential boundary with persistent authorization audit. */
  public HibernateCredentialScopedRepositoryAccessPolicy(
      SessionFactory sessionFactory,
      RepositoryAccessPolicy<GitAccessContext> repositoryPolicy,
      SecurityAccessAuditRecorder auditRecorder) {
    this(sessionFactory, repositoryPolicy, auditRecorder, Clock.systemUTC());
  }

  HibernateCredentialScopedRepositoryAccessPolicy(
      SessionFactory sessionFactory,
      RepositoryAccessPolicy<GitAccessContext> repositoryPolicy,
      SecurityAccessAuditRecorder auditRecorder,
      Clock clock) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
    this.credentialPolicy =
        new CredentialScopedRepositoryAccessPolicy(
            Objects.requireNonNull(repositoryPolicy, "repositoryPolicy"), auditRecorder);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void require(AuthenticatedGitAccess access, RepositoryAccessRequest request) {
    Objects.requireNonNull(access, "access");
    Objects.requireNonNull(request, "request");
    if (access.credentialKind() == SecurityCredentialKind.ACCESS_TOKEN) {
      requireCurrentAccessToken(access, request);
    }
    credentialPolicy.require(access, request);
  }

  private void requireCurrentAccessToken(
      AuthenticatedGitAccess access, RepositoryAccessRequest request) {
    try (Session session = sessionFactory.openSession()) {
      SecurityAccessTokenEntity token =
          session.find(SecurityAccessTokenEntity.class, access.credentialId());
      if (token == null
          || !access.context().principalId().equals(token.getPrincipalId())) {
        deny(
            access,
            request,
            SecurityAuthenticationReason.INVALID_CREDENTIALS,
            access.credentialVersion());
        return;
      }

      if (token.getRevokedAt() != null) {
        deny(
            access,
            request,
            SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED,
            token.getSecurityVersion());
        return;
      }

      Instant now = Instant.now(clock);
      if (token.getExpiresAt() != null && !now.isBefore(token.getExpiresAt())) {
        deny(
            access,
            request,
            SecurityAuthenticationReason.ACCESS_TOKEN_EXPIRED,
            token.getSecurityVersion());
        return;
      }

      boolean versionChanged = token.getSecurityVersion() != access.credentialVersion();
      boolean scopesChanged =
          !HibernateSecurityCredentialService.serializeScopes(access.credentialScopes())
              .equals(token.getPermissionScopes());
      if (versionChanged || scopesChanged) {
        deny(
            access,
            request,
            SecurityAuthenticationReason.INVALID_CREDENTIALS,
            token.getSecurityVersion());
        return;
      }
    } catch (RepositoryAccessDeniedException | SecurityAuditPersistenceException handled) {
      throw handled;
    } catch (RuntimeException failure) {
      SecurityAuditSupport.fail(
          auditRecorder,
          SecurityAccessAuditRecord.failed(access.context(), request, failure),
          failure);
    }
  }

  private void deny(
      AuthenticatedGitAccess access,
      RepositoryAccessRequest request,
      SecurityAuthenticationReason reason,
      long currentVersion) {
    RepositoryAccessDeniedException denied =
        new RepositoryAccessDeniedException(
            request, reason.name(), access.credentialId(), currentVersion);
    SecurityAuditSupport.deny(
        auditRecorder,
        SecurityAccessAuditRecord.denied(access.context(), denied),
        denied);
  }

}
