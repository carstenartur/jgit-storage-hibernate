/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityLocalCredentialEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import jakarta.persistence.LockModeType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Framework-neutral Hibernate service for local passwords and one-way access tokens.
 *
 * <p>Successful state transitions and their identity-audit records share one Hibernate transaction
 * when {@link HibernateSecurityIdentityAuditService} is selected. Authentication denials never
 * return a repository context. Passwords and token values are never persisted or included in audit
 * evidence.
 */
public final class HibernateSecurityCredentialService {

  private static final int MAX_LOGIN_NAME_LENGTH = 256;
  private static final int MAX_QUERY_LIMIT = 1000;
  private static final int TOKEN_LOOKUP_BYTES = 12;
  private static final int TOKEN_SECRET_BYTES = 32;
  private static final Pattern TOKEN_PATTERN =
      Pattern.compile("^(jsh_[A-Za-z0-9_-]{16})\\.([A-Za-z0-9_-]{43})$");
  private static final String DUMMY_TOKEN =
      "jsh_AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
  private static final char[] DUMMY_PASSWORD = "invalid-credential-timing-value".toCharArray();
  private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

  private final SessionFactory sessionFactory;
  private final PasswordHasher passwordHasher;
  private final AccessTokenHasher accessTokenHasher;
  private final PasswordLockoutPolicy lockoutPolicy;
  private final SecurityManagementPolicy managementPolicy;
  private final SecurityIdentityAuditRecorder auditRecorder;
  private final Clock clock;
  private final Supplier<String> tokenIdSupplier;
  private final Supplier<String> tokenValueSupplier;
  private final PasswordHash dummyPasswordHash;
  private final AccessTokenHash dummyTokenHash;

  /**
   * Create a credential service with the default lockout policy and persistent transactional audit.
   */
  public HibernateSecurityCredentialService(
      SessionFactory sessionFactory,
      PasswordHasher passwordHasher,
      AccessTokenHasher accessTokenHasher,
      SecurityManagementPolicy managementPolicy,
      HibernateSecurityIdentityAuditService auditService) {
    this(
        sessionFactory,
        passwordHasher,
        accessTokenHasher,
        PasswordLockoutPolicy.DEFAULT,
        managementPolicy,
        auditService,
        Clock.systemUTC(),
        () -> UUID.randomUUID().toString(),
        HibernateSecurityCredentialService::generateTokenValue);
  }

  /**
   * Create a credential service with explicit operational policy and audit behavior.
   *
   * <p>Use {@link SecurityIdentityAuditRecorder#NONE} only when the application deliberately accepts
   * the absence of persistent identity audit. A {@link HibernateSecurityIdentityAuditService}
   * participates in the same transaction as successful state changes.
   */
  public HibernateSecurityCredentialService(
      SessionFactory sessionFactory,
      PasswordHasher passwordHasher,
      AccessTokenHasher accessTokenHasher,
      PasswordLockoutPolicy lockoutPolicy,
      SecurityManagementPolicy managementPolicy,
      SecurityIdentityAuditRecorder auditRecorder) {
    this(
        sessionFactory,
        passwordHasher,
        accessTokenHasher,
        lockoutPolicy,
        managementPolicy,
        auditRecorder,
        Clock.systemUTC(),
        () -> UUID.randomUUID().toString(),
        HibernateSecurityCredentialService::generateTokenValue);
  }

  HibernateSecurityCredentialService(
      SessionFactory sessionFactory,
      PasswordHasher passwordHasher,
      AccessTokenHasher accessTokenHasher,
      PasswordLockoutPolicy lockoutPolicy,
      SecurityManagementPolicy managementPolicy,
      SecurityIdentityAuditRecorder auditRecorder,
      Clock clock,
      Supplier<String> tokenIdSupplier,
      Supplier<String> tokenValueSupplier) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
    this.accessTokenHasher = Objects.requireNonNull(accessTokenHasher, "accessTokenHasher");
    this.lockoutPolicy = Objects.requireNonNull(lockoutPolicy, "lockoutPolicy");
    this.managementPolicy = Objects.requireNonNull(managementPolicy, "managementPolicy");
    this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tokenIdSupplier = Objects.requireNonNull(tokenIdSupplier, "tokenIdSupplier");
    this.tokenValueSupplier = Objects.requireNonNull(tokenValueSupplier, "tokenValueSupplier");

    char[] dummy = DUMMY_PASSWORD.clone();
    try {
      this.dummyPasswordHash = passwordHasher.hash(dummy);
    } finally {
      Arrays.fill(dummy, '\0');
    }
    this.dummyTokenHash = accessTokenHasher.hash(DUMMY_TOKEN);
  }

  /** Set or replace one principal's local password verifier. */
  public LocalCredentialMetadata setPassword(
      SecurityManagementRequest request, char[] password) {
    requireOperation(request, SecurityManagementOperation.SET_PASSWORD);
    requireManagement(request, SecurityCredentialKind.PASSWORD, null);
    PasswordHash passwordHash = passwordHasher.hash(password);
    try {
      return sessionFactory.fromTransaction(
          session -> {
            requirePrincipal(session, request.subjectPrincipalId());
            Instant now = Instant.now(clock);
            SecurityLocalCredentialEntity credential =
                session.find(
                    SecurityLocalCredentialEntity.class,
                    request.subjectPrincipalId(),
                    LockModeType.PESSIMISTIC_WRITE);
            boolean newCredential = credential == null;
            if (newCredential) {
              credential = new SecurityLocalCredentialEntity();
              credential.setPrincipalId(request.subjectPrincipalId());
              credential.setSecurityVersion(1);
            } else {
              credential.setSecurityVersion(nextVersion(credential.getSecurityVersion()));
            }
            credential.setPasswordAlgorithm(passwordHash.algorithm());
            credential.setPasswordVersion(passwordHash.version());
            credential.setPasswordHash(passwordHash.encodedHash());
            credential.setChangedAt(now);
            credential.setFailedAttemptCount(0);
            credential.setLockedUntil(null);
            if (newCredential) {
              session.persist(credential);
            }
            record(
                session,
                SecurityIdentityAuditRecord.management(
                    request,
                    SecurityCredentialKind.PASSWORD,
                    null,
                    SecurityAuditOutcome.ALLOWED,
                    "PASSWORD_SET"));
            return toMetadata(credential);
          });
    } catch (RuntimeException failure) {
      auditFailedManagement(
          request, SecurityCredentialKind.PASSWORD, null, "PASSWORD_SET_FAILED", failure);
      throw failure;
    }
  }

  /** Remove a local password verifier; repeated removal is an allowed no-op. */
  public boolean removePassword(SecurityManagementRequest request) {
    requireOperation(request, SecurityManagementOperation.REMOVE_PASSWORD);
    requireManagement(request, SecurityCredentialKind.PASSWORD, null);
    try {
      return sessionFactory.fromTransaction(
          session -> {
            requirePrincipal(session, request.subjectPrincipalId());
            SecurityLocalCredentialEntity credential =
                session.find(
                    SecurityLocalCredentialEntity.class,
                    request.subjectPrincipalId(),
                    LockModeType.PESSIMISTIC_WRITE);
            boolean removed = credential != null;
            if (credential != null) {
              session.remove(credential);
            }
            record(
                session,
                SecurityIdentityAuditRecord.management(
                    request,
                    SecurityCredentialKind.PASSWORD,
                    null,
                    SecurityAuditOutcome.ALLOWED,
                    removed ? "PASSWORD_REMOVED" : "PASSWORD_ALREADY_REMOVED"));
            return removed;
          });
    } catch (RuntimeException failure) {
      auditFailedManagement(
          request, SecurityCredentialKind.PASSWORD, null, "PASSWORD_REMOVE_FAILED", failure);
      throw failure;
    }
  }

  /** Clear password failure counters and temporary lockout state when a credential exists. */
  public Optional<LocalCredentialMetadata> unlockPassword(SecurityManagementRequest request) {
    requireOperation(request, SecurityManagementOperation.UNLOCK_PASSWORD);
    requireManagement(request, SecurityCredentialKind.PASSWORD, null);
    try {
      return sessionFactory.fromTransaction(
          session -> {
            requirePrincipal(session, request.subjectPrincipalId());
            SecurityLocalCredentialEntity credential =
                session.find(
                    SecurityLocalCredentialEntity.class,
                    request.subjectPrincipalId(),
                    LockModeType.PESSIMISTIC_WRITE);
            if (credential != null
                && (credential.getFailedAttemptCount() != 0 || credential.getLockedUntil() != null)) {
              credential.setFailedAttemptCount(0);
              credential.setLockedUntil(null);
              credential.setSecurityVersion(nextVersion(credential.getSecurityVersion()));
            }
            record(
                session,
                SecurityIdentityAuditRecord.management(
                    request,
                    SecurityCredentialKind.PASSWORD,
                    null,
                    SecurityAuditOutcome.ALLOWED,
                    credential == null ? "PASSWORD_NOT_CONFIGURED" : "PASSWORD_UNLOCKED"));
            return Optional.ofNullable(credential).map(HibernateSecurityCredentialService::toMetadata);
          });
    } catch (RuntimeException failure) {
      auditFailedManagement(
          request, SecurityCredentialKind.PASSWORD, null, "PASSWORD_UNLOCK_FAILED", failure);
      throw failure;
    }
  }

  /** Issue a high-entropy access token and return its plaintext exactly once. */
  public IssuedAccessToken issueAccessToken(
      SecurityManagementRequest request,
      Set<GitRepositoryPermission> permissionScopes,
      Instant expiresAt) {
    requireOperation(request, SecurityManagementOperation.ISSUE_ACCESS_TOKEN);
    requireManagement(request, SecurityCredentialKind.ACCESS_TOKEN, null);
    Set<GitRepositoryPermission> scopes = immutableScopes(permissionScopes);
    Instant issuedAt = Instant.now(clock);
    if (expiresAt != null && !expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("expiresAt must be after the issue time");
    }

    String tokenId = required("generated tokenId", tokenIdSupplier.get(), 128);
    String tokenValue = requiredTokenValue(tokenValueSupplier.get());
    String tokenPrefix = tokenPrefix(tokenValue);
    AccessTokenHash tokenHash = accessTokenHasher.hash(tokenValue);
    String serializedScopes = serializeScopes(scopes);
    try {
      AccessTokenMetadata metadata =
          sessionFactory.fromTransaction(
              session -> {
                requirePrincipal(session, request.subjectPrincipalId());
                if (session.find(SecurityAccessTokenEntity.class, tokenId) != null) {
                  throw new HibernateStorageException("Generated access-token ID already exists");
                }
                SecurityAccessTokenEntity token = new SecurityAccessTokenEntity();
                token.setTokenId(tokenId);
                token.setPrincipalId(request.subjectPrincipalId());
                token.setTokenPrefix(tokenPrefix);
                token.setTokenAlgorithm(tokenHash.algorithm());
                token.setTokenVersion(tokenHash.version());
                token.setTokenHash(tokenHash.encodedHash());
                token.setPermissionScopes(serializedScopes);
                token.setIssuedAt(issuedAt);
                token.setExpiresAt(expiresAt);
                token.setLastUsedAt(null);
                token.setRevokedAt(null);
                token.setIssuedBy(request.actor().principalId());
                token.setSecurityVersion(1);
                session.persist(token);
                record(
                    session,
                    SecurityIdentityAuditRecord.management(
                        request,
                        SecurityCredentialKind.ACCESS_TOKEN,
                        tokenId,
                        SecurityAuditOutcome.ALLOWED,
                        "ACCESS_TOKEN_ISSUED"));
                return toMetadata(token);
              });
      return new IssuedAccessToken(metadata, tokenValue);
    } catch (RuntimeException failure) {
      auditFailedManagement(
          request,
          SecurityCredentialKind.ACCESS_TOKEN,
          tokenId,
          "ACCESS_TOKEN_ISSUE_FAILED",
          failure);
      throw failure;
    }
  }

  /** Revoke an access token; repeated revocation returns the existing metadata unchanged. */
  public AccessTokenMetadata revokeAccessToken(SecurityManagementRequest request) {
    requireOperation(request, SecurityManagementOperation.REVOKE_ACCESS_TOKEN);
    requireManagement(request, SecurityCredentialKind.ACCESS_TOKEN, request.credentialId());
    try {
      return sessionFactory.fromTransaction(
          session -> {
            requirePrincipal(session, request.subjectPrincipalId());
            SecurityAccessTokenEntity token =
                session.find(
                    SecurityAccessTokenEntity.class,
                    request.credentialId(),
                    LockModeType.PESSIMISTIC_WRITE);
            if (token == null || !request.subjectPrincipalId().equals(token.getPrincipalId())) {
              throw new HibernateStorageException(
                  "Access token does not exist for the requested principal");
            }
            boolean newlyRevoked = token.getRevokedAt() == null;
            if (newlyRevoked) {
              token.setRevokedAt(Instant.now(clock));
              token.setSecurityVersion(nextVersion(token.getSecurityVersion()));
            }
            record(
                session,
                SecurityIdentityAuditRecord.management(
                    request,
                    SecurityCredentialKind.ACCESS_TOKEN,
                    token.getTokenId(),
                    SecurityAuditOutcome.ALLOWED,
                    newlyRevoked ? "ACCESS_TOKEN_REVOKED" : "ACCESS_TOKEN_ALREADY_REVOKED"));
            return toMetadata(token);
          });
    } catch (RuntimeException failure) {
      auditFailedManagement(
          request,
          SecurityCredentialKind.ACCESS_TOKEN,
          request.credentialId(),
          "ACCESS_TOKEN_REVOKE_FAILED",
          failure);
      throw failure;
    }
  }

  /** Authenticate a local password by unique login name. */
  public AuthenticatedGitAccess authenticatePassword(
      String loginName, char[] password, SecurityAuthenticationTrace trace) {
    Objects.requireNonNull(trace, "trace");
    AuthenticationOutcome outcome;
    try {
      outcome =
          sessionFactory.fromTransaction(
              session -> authenticatePassword(session, loginName, password, trace));
    } catch (DeniedAuthenticationAuditFailure denied) {
      throw denied.authenticationException();
    } catch (SecurityIdentityAuditPersistenceException auditFailure) {
      throw auditFailure;
    } catch (RuntimeException failure) {
      throw failedAuthentication(
          SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
          null,
          trace,
          SecurityCredentialKind.PASSWORD,
          null,
          failure);
    }
    return requireAuthenticated(outcome);
  }

  /** Authenticate a one-way access token by its non-secret lookup prefix. */
  public AuthenticatedGitAccess authenticateAccessToken(
      String tokenValue, SecurityAuthenticationTrace trace) {
    Objects.requireNonNull(trace, "trace");
    Matcher matcher = tokenValue == null ? null : TOKEN_PATTERN.matcher(tokenValue);
    if (matcher == null || !matcher.matches()) {
      SecurityAuthenticationException denied =
          new SecurityAuthenticationException(
              SecurityAuthenticationReason.MALFORMED_ACCESS_TOKEN);
      auditDeniedOutsideTransaction(
          SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
          null,
          trace,
          SecurityCredentialKind.ACCESS_TOKEN,
          null,
          SecurityAuthenticationReason.MALFORMED_ACCESS_TOKEN,
          denied);
      throw denied;
    }

    String prefix = matcher.group(1);
    AuthenticationOutcome outcome;
    try {
      outcome =
          sessionFactory.fromTransaction(
              session -> authenticateAccessToken(session, tokenValue, prefix, trace));
    } catch (DeniedAuthenticationAuditFailure denied) {
      throw denied.authenticationException();
    } catch (SecurityIdentityAuditPersistenceException auditFailure) {
      throw auditFailure;
    } catch (RuntimeException failure) {
      throw failedAuthentication(
          SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
          null,
          trace,
          SecurityCredentialKind.ACCESS_TOKEN,
          null,
          failure);
    }
    return requireAuthenticated(outcome);
  }

  /** Find non-secret password metadata for one principal. */
  public Optional<LocalCredentialMetadata> findLocalCredential(String principalId) {
    String id = required("principalId", principalId, 128);
    try (Session session = sessionFactory.openSession()) {
      return Optional.ofNullable(session.find(SecurityLocalCredentialEntity.class, id))
          .map(HibernateSecurityCredentialService::toMetadata);
    }
  }

  /** Find non-secret metadata for one access token. */
  public Optional<AccessTokenMetadata> findAccessToken(String tokenId) {
    String id = required("tokenId", tokenId, 128);
    try (Session session = sessionFactory.openSession()) {
      return Optional.ofNullable(session.find(SecurityAccessTokenEntity.class, id))
          .map(HibernateSecurityCredentialService::toMetadata);
    }
  }

  /** Find newest access tokens for one principal with a bounded result size. */
  public List<AccessTokenMetadata> findAccessTokens(String principalId, int limit) {
    String id = required("principalId", principalId, 128);
    int boundedLimit = queryLimit(limit);
    try (Session session = sessionFactory.openSession()) {
      return session
          .createSelectionQuery(
              "FROM SecurityAccessToken t WHERE t.principalId = :principalId "
                  + "ORDER BY t.issuedAt DESC, t.tokenId DESC",
              SecurityAccessTokenEntity.class)
          .setParameter("principalId", id)
          .setMaxResults(boundedLimit)
          .getResultList()
          .stream()
          .map(HibernateSecurityCredentialService::toMetadata)
          .toList();
    }
  }

  private AuthenticationOutcome authenticatePassword(
      Session session,
      String loginName,
      char[] password,
      SecurityAuthenticationTrace trace) {
    boolean inputValid =
        loginName != null
            && !loginName.isBlank()
            && loginName.length() <= MAX_LOGIN_NAME_LENGTH
            && password != null
            && password.length > 0
            && password.length <= 1024;
    SecurityPrincipalEntity lookupPrincipal =
        inputValid
            ? session
                .createSelectionQuery(
                    "FROM SecurityPrincipal p WHERE p.loginName = :loginName",
                    SecurityPrincipalEntity.class)
                .setParameter("loginName", loginName)
                .getResultStream()
                .findFirst()
                .orElse(null)
            : null;
    SecurityPrincipalEntity principal =
        lookupPrincipal == null
            ? null
            : session.find(
                SecurityPrincipalEntity.class,
                lookupPrincipal.getPrincipalId(),
                LockModeType.PESSIMISTIC_READ);
    SecurityLocalCredentialEntity credential =
        principal == null
            ? null
            : session.find(
                SecurityLocalCredentialEntity.class,
                principal.getPrincipalId(),
                LockModeType.PESSIMISTIC_WRITE);

    char[] supplied = inputValid ? password : DUMMY_PASSWORD.clone();
    PasswordHash expected = credential == null ? dummyPasswordHash : passwordHash(credential);
    boolean matches;
    try {
      matches = passwordHasher.verify(supplied, expected);
    } catch (IllegalArgumentException malformedInput) {
      matches = false;
    } finally {
      if (!inputValid) {
        Arrays.fill(supplied, '\0');
      }
    }

    String principalId = principal == null ? null : principal.getPrincipalId();
    String credentialId = credential == null ? null : principalId;
    if (!inputValid || principal == null) {
      return deniedPassword(
          session,
          principalId,
          trace,
          credentialId,
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          null);
    }
    if (principal.getStatus() != SecurityPrincipalStatus.ACTIVE) {
      return deniedPassword(
          session,
          principalId,
          trace,
          credentialId,
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE,
          null);
    }
    if (credential == null) {
      return deniedPassword(
          session,
          principalId,
          trace,
          null,
          SecurityAuthenticationReason.CREDENTIAL_NOT_CONFIGURED,
          null);
    }

    Instant now = Instant.now(clock);
    if (credential.getLockedUntil() != null && now.isBefore(credential.getLockedUntil())) {
      return deniedPassword(
          session,
          principalId,
          trace,
          credentialId,
          SecurityAuthenticationReason.PASSWORD_LOCKED,
          credential.getLockedUntil());
    }
    if (credential.getLockedUntil() != null) {
      credential.setLockedUntil(null);
      credential.setFailedAttemptCount(0);
      credential.setSecurityVersion(nextVersion(credential.getSecurityVersion()));
    }

    if (!matches) {
      int failedAttempts =
          Math.min(
              lockoutPolicy.maximumFailedAttempts(), credential.getFailedAttemptCount() + 1);
      credential.setFailedAttemptCount(failedAttempts);
      Instant retryAt = null;
      SecurityAuthenticationReason reason = SecurityAuthenticationReason.INVALID_CREDENTIALS;
      if (failedAttempts >= lockoutPolicy.maximumFailedAttempts()) {
        retryAt = now.plus(lockoutPolicy.lockDuration());
        credential.setLockedUntil(retryAt);
        reason = SecurityAuthenticationReason.PASSWORD_LOCKED;
      }
      credential.setSecurityVersion(nextVersion(credential.getSecurityVersion()));
      return deniedPassword(session, principalId, trace, credentialId, reason, retryAt);
    }

    boolean changed =
        credential.getFailedAttemptCount() != 0 || credential.getLockedUntil() != null;
    credential.setFailedAttemptCount(0);
    credential.setLockedUntil(null);
    if (passwordHasher.needsRehash(passwordHash(credential))) {
      PasswordHash replacement = passwordHasher.hash(password);
      credential.setPasswordAlgorithm(replacement.algorithm());
      credential.setPasswordVersion(replacement.version());
      credential.setPasswordHash(replacement.encodedHash());
      credential.setChangedAt(now);
      changed = true;
    }
    if (changed) {
      credential.setSecurityVersion(nextVersion(credential.getSecurityVersion()));
    }

    GitAccessContext context =
        new GitAccessContext(
            principalId,
            Set.of(),
            "password",
            trace.sessionId(),
            trace.correlationId(),
            Map.of());
    AuthenticatedGitAccess access =
        AuthenticatedGitAccess.unrestricted(
            context,
            SecurityCredentialKind.PASSWORD,
            credentialId,
            credential.getSecurityVersion());
    record(
        session,
        SecurityIdentityAuditRecord.authentication(
            SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
            principalId,
            trace,
            SecurityCredentialKind.PASSWORD,
            credentialId,
            SecurityAuditOutcome.ALLOWED,
            SecurityAuthenticationReason.PASSWORD_AUTHENTICATED));
    return AuthenticationOutcome.authenticated(access);
  }

  private AuthenticationOutcome authenticateAccessToken(
      Session session,
      String tokenValue,
      String tokenPrefix,
      SecurityAuthenticationTrace trace) {
    SecurityAccessTokenEntity lookupToken =
        session
            .createSelectionQuery(
                "FROM SecurityAccessToken t WHERE t.tokenPrefix = :tokenPrefix",
                SecurityAccessTokenEntity.class)
            .setParameter("tokenPrefix", tokenPrefix)
            .getResultStream()
            .findFirst()
            .orElse(null);
    SecurityAccessTokenEntity token =
        lookupToken == null
            ? null
            : session.find(
                SecurityAccessTokenEntity.class,
                lookupToken.getTokenId(),
                LockModeType.PESSIMISTIC_WRITE);
    AccessTokenHash expected = token == null ? dummyTokenHash : accessTokenHash(token);
    boolean matches = accessTokenHasher.verify(tokenValue, expected);
    if (token == null || !matches) {
      return deniedToken(
          session,
          null,
          trace,
          null,
          SecurityAuthenticationReason.INVALID_CREDENTIALS);
    }

    SecurityPrincipalEntity principal =
        session.find(
            SecurityPrincipalEntity.class,
            token.getPrincipalId(),
            LockModeType.PESSIMISTIC_READ);
    if (principal == null || principal.getStatus() != SecurityPrincipalStatus.ACTIVE) {
      return deniedToken(
          session,
          token.getPrincipalId(),
          trace,
          token.getTokenId(),
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE);
    }
    if (token.getRevokedAt() != null) {
      return deniedToken(
          session,
          token.getPrincipalId(),
          trace,
          token.getTokenId(),
          SecurityAuthenticationReason.ACCESS_TOKEN_REVOKED);
    }

    Instant now = Instant.now(clock);
    if (token.getExpiresAt() != null && !now.isBefore(token.getExpiresAt())) {
      return deniedToken(
          session,
          token.getPrincipalId(),
          trace,
          token.getTokenId(),
          SecurityAuthenticationReason.ACCESS_TOKEN_EXPIRED);
    }

    Set<GitRepositoryPermission> scopes = parseScopes(token.getPermissionScopes());
    token.setLastUsedAt(now);
    token.setSecurityVersion(nextVersion(token.getSecurityVersion()));
    GitAccessContext context =
        new GitAccessContext(
            token.getPrincipalId(),
            Set.of(),
            "access_token",
            trace.sessionId(),
            trace.correlationId(),
            Map.of());
    AuthenticatedGitAccess access =
        new AuthenticatedGitAccess(
            context,
            SecurityCredentialKind.ACCESS_TOKEN,
            token.getTokenId(),
            token.getSecurityVersion(),
            scopes);
    record(
        session,
        SecurityIdentityAuditRecord.authentication(
            SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
            token.getPrincipalId(),
            trace,
            SecurityCredentialKind.ACCESS_TOKEN,
            token.getTokenId(),
            SecurityAuditOutcome.ALLOWED,
            SecurityAuthenticationReason.ACCESS_TOKEN_AUTHENTICATED));
    return AuthenticationOutcome.authenticated(access);
  }

  private AuthenticationOutcome deniedPassword(
      Session session,
      String principalId,
      SecurityAuthenticationTrace trace,
      String credentialId,
      SecurityAuthenticationReason reason,
      Instant retryAt) {
    recordDenied(
        session,
        SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION,
        principalId,
        trace,
        SecurityCredentialKind.PASSWORD,
        credentialId,
        reason,
        retryAt);
    return AuthenticationOutcome.denied(reason, retryAt);
  }

  private AuthenticationOutcome deniedToken(
      Session session,
      String principalId,
      SecurityAuthenticationTrace trace,
      String credentialId,
      SecurityAuthenticationReason reason) {
    recordDenied(
        session,
        SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION,
        principalId,
        trace,
        SecurityCredentialKind.ACCESS_TOKEN,
        credentialId,
        reason,
        null);
    return AuthenticationOutcome.denied(reason, null);
  }

  private void recordDenied(
      Session session,
      SecurityIdentityAuditOperation operation,
      String principalId,
      SecurityAuthenticationTrace trace,
      SecurityCredentialKind credentialKind,
      String credentialId,
      SecurityAuthenticationReason reason,
      Instant retryAt) {
    try {
      record(
          session,
          SecurityIdentityAuditRecord.authentication(
              operation,
              principalId,
              trace,
              credentialKind,
              credentialId,
              SecurityAuditOutcome.DENIED,
              reason));
    } catch (RuntimeException auditFailure) {
      SecurityAuthenticationException denied =
          new SecurityAuthenticationException(reason, retryAt, null);
      denied.addSuppressed(asIdentityAuditFailure(auditFailure));
      throw new DeniedAuthenticationAuditFailure(denied);
    }
  }

  private AuthenticatedGitAccess requireAuthenticated(AuthenticationOutcome outcome) {
    if (outcome.access() != null) {
      return outcome.access();
    }
    throw new SecurityAuthenticationException(outcome.reason(), outcome.retryAt(), null);
  }

  private void requireManagement(
      SecurityManagementRequest request,
      SecurityCredentialKind credentialKind,
      String credentialId) {
    try {
      managementPolicy.require(request);
    } catch (SecurityManagementDeniedException denied) {
      try {
        auditRecorder.record(
            SecurityIdentityAuditRecord.management(
                request,
                credentialKind,
                credentialId,
                SecurityAuditOutcome.DENIED,
                denied.reasonCode()));
      } catch (RuntimeException auditFailure) {
        denied.addSuppressed(asIdentityAuditFailure(auditFailure));
      }
      throw denied;
    } catch (RuntimeException failure) {
      auditFailedManagement(
          request,
          credentialKind,
          credentialId,
          "MANAGEMENT_POLICY_FAILURE",
          failure);
      throw failure;
    }
  }

  private void auditFailedManagement(
      SecurityManagementRequest request,
      SecurityCredentialKind credentialKind,
      String credentialId,
      String reasonCode,
      RuntimeException failure) {
    if (failure instanceof SecurityIdentityAuditPersistenceException) {
      return;
    }
    try {
      auditRecorder.record(
          SecurityIdentityAuditRecord.failedManagement(
              request, credentialKind, credentialId, reasonCode, failure));
    } catch (RuntimeException auditFailure) {
      failure.addSuppressed(asIdentityAuditFailure(auditFailure));
    }
  }

  private SecurityAuthenticationException failedAuthentication(
      SecurityIdentityAuditOperation operation,
      String principalId,
      SecurityAuthenticationTrace trace,
      SecurityCredentialKind credentialKind,
      String credentialId,
      RuntimeException failure) {
    SecurityAuthenticationException authenticationFailure =
        new SecurityAuthenticationException(
            SecurityAuthenticationReason.AUTHENTICATION_FAILURE, null, failure);
    try {
      auditRecorder.record(
          SecurityIdentityAuditRecord.failedAuthentication(
              operation, principalId, trace, credentialKind, credentialId, failure));
    } catch (RuntimeException auditFailure) {
      authenticationFailure.addSuppressed(asIdentityAuditFailure(auditFailure));
    }
    return authenticationFailure;
  }

  private void auditDeniedOutsideTransaction(
      SecurityIdentityAuditOperation operation,
      String principalId,
      SecurityAuthenticationTrace trace,
      SecurityCredentialKind credentialKind,
      String credentialId,
      SecurityAuthenticationReason reason,
      SecurityAuthenticationException denied) {
    try {
      auditRecorder.record(
          SecurityIdentityAuditRecord.authentication(
              operation,
              principalId,
              trace,
              credentialKind,
              credentialId,
              SecurityAuditOutcome.DENIED,
              reason));
    } catch (RuntimeException auditFailure) {
      denied.addSuppressed(asIdentityAuditFailure(auditFailure));
    }
  }

  private void record(Session session, SecurityIdentityAuditRecord record) {
    if (auditRecorder instanceof HibernateSecurityIdentityAuditService persistentAudit) {
      persistentAudit.record(session, record);
    } else {
      auditRecorder.record(record);
    }
  }

  private static SecurityIdentityAuditPersistenceException asIdentityAuditFailure(
      RuntimeException failure) {
    return failure instanceof SecurityIdentityAuditPersistenceException persistenceFailure
        ? persistenceFailure
        : new SecurityIdentityAuditPersistenceException(
            "Could not append credential lifecycle audit evidence", failure);
  }

  private static void requireOperation(
      SecurityManagementRequest request, SecurityManagementOperation expected) {
    Objects.requireNonNull(request, "request");
    if (request.operation() != expected) {
      throw new IllegalArgumentException(
          "Expected " + expected + " request but received " + request.operation());
    }
  }

  private static SecurityPrincipalEntity requirePrincipal(Session session, String principalId) {
    SecurityPrincipalEntity principal =
        session.find(SecurityPrincipalEntity.class, principalId, LockModeType.PESSIMISTIC_READ);
    if (principal == null) {
      throw new HibernateStorageException("Security principal does not exist: " + principalId);
    }
    return principal;
  }

  private static PasswordHash passwordHash(SecurityLocalCredentialEntity credential) {
    return new PasswordHash(
        credential.getPasswordAlgorithm(),
        credential.getPasswordVersion(),
        credential.getPasswordHash());
  }

  private static AccessTokenHash accessTokenHash(SecurityAccessTokenEntity token) {
    return new AccessTokenHash(
        token.getTokenAlgorithm(), token.getTokenVersion(), token.getTokenHash());
  }

  private static LocalCredentialMetadata toMetadata(SecurityLocalCredentialEntity credential) {
    return new LocalCredentialMetadata(
        credential.getPrincipalId(),
        credential.getChangedAt(),
        credential.getFailedAttemptCount(),
        credential.getLockedUntil(),
        credential.getSecurityVersion());
  }

  private static AccessTokenMetadata toMetadata(SecurityAccessTokenEntity token) {
    return new AccessTokenMetadata(
        token.getTokenId(),
        token.getPrincipalId(),
        token.getTokenPrefix(),
        parseScopes(token.getPermissionScopes()),
        token.getIssuedAt(),
        token.getExpiresAt(),
        token.getLastUsedAt(),
        token.getRevokedAt(),
        token.getIssuedBy(),
        token.getSecurityVersion());
  }

  private static Set<GitRepositoryPermission> immutableScopes(
      Set<GitRepositoryPermission> permissionScopes) {
    Objects.requireNonNull(permissionScopes, "permissionScopes");
    if (permissionScopes.isEmpty()) {
      throw new IllegalArgumentException("permissionScopes must not be empty");
    }
    if (permissionScopes.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("permissionScopes must not contain null");
    }
    return Set.copyOf(permissionScopes);
  }

  private static String serializeScopes(Set<GitRepositoryPermission> scopes) {
    String serialized =
        scopes.stream()
            .sorted()
            .map(Enum::name)
            .collect(Collectors.joining(","));
    if (serialized.length() > 512) {
      throw new IllegalArgumentException("serialized permissionScopes exceed 512 characters");
    }
    return serialized;
  }

  private static Set<GitRepositoryPermission> parseScopes(String serialized) {
    if (serialized == null || serialized.isBlank()) {
      throw new HibernateStorageException("Persisted access-token scopes are empty");
    }
    EnumSet<GitRepositoryPermission> scopes = EnumSet.noneOf(GitRepositoryPermission.class);
    try {
      for (String value : serialized.split(",", -1)) {
        if (value.isBlank()) {
          throw new IllegalArgumentException("blank scope");
        }
        scopes.add(GitRepositoryPermission.valueOf(value));
      }
    } catch (IllegalArgumentException malformed) {
      throw new HibernateStorageException("Persisted access-token scopes are malformed", malformed);
    }
    if (scopes.isEmpty()) {
      throw new HibernateStorageException("Persisted access-token scopes are empty");
    }
    return Set.copyOf(scopes);
  }

  private static String requiredTokenValue(String tokenValue) {
    if (tokenValue == null || !TOKEN_PATTERN.matcher(tokenValue).matches()) {
      throw new IllegalArgumentException(
          "generated tokenValue must use the jsh_<lookup>.<secret> format");
    }
    return tokenValue;
  }

  private static String tokenPrefix(String tokenValue) {
    Matcher matcher = TOKEN_PATTERN.matcher(tokenValue);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("tokenValue is malformed");
    }
    return matcher.group(1);
  }

  private static String generateTokenValue() {
    return "jsh_"
        + randomBase64Url(TOKEN_RANDOM, TOKEN_LOOKUP_BYTES)
        + "."
        + randomBase64Url(TOKEN_RANDOM, TOKEN_SECRET_BYTES);
  }

  private static String randomBase64Url(SecureRandom random, int byteCount) {
    byte[] bytes = new byte[byteCount];
    random.nextBytes(bytes);
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static int queryLimit(int limit) {
    if (limit < 1 || limit > MAX_QUERY_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_QUERY_LIMIT);
    }
    return limit;
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }

  private static long nextVersion(long current) {
    if (current < 0) {
      throw new HibernateStorageException("Persisted security version must not be negative");
    }
    try {
      return Math.incrementExact(current);
    } catch (ArithmeticException overflow) {
      throw new HibernateStorageException("Security version overflow", overflow);
    }
  }

  private record AuthenticationOutcome(
      AuthenticatedGitAccess access, SecurityAuthenticationReason reason, Instant retryAt) {

    static AuthenticationOutcome authenticated(AuthenticatedGitAccess access) {
      return new AuthenticationOutcome(Objects.requireNonNull(access, "access"), null, null);
    }

    static AuthenticationOutcome denied(SecurityAuthenticationReason reason, Instant retryAt) {
      SecurityAuthenticationReason failureReason = Objects.requireNonNull(reason, "reason");
      if (failureReason.authenticated()) {
        throw new IllegalArgumentException("authenticated reason cannot represent denial");
      }
      return new AuthenticationOutcome(null, failureReason, retryAt);
    }
  }

  private static final class DeniedAuthenticationAuditFailure extends RuntimeException {

    private final SecurityAuthenticationException authenticationException;

    DeniedAuthenticationAuditFailure(SecurityAuthenticationException authenticationException) {
      super(authenticationException);
      this.authenticationException =
          Objects.requireNonNull(authenticationException, "authenticationException");
    }

    SecurityAuthenticationException authenticationException() {
      return authenticationException;
    }
  }
}
