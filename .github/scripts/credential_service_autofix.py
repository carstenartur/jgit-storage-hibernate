#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "jgit-storage-hibernate-security/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/security/"
    "HibernateSecurityCredentialService.java"
)
text = path.read_text(encoding="utf-8")

replacements = [
    (
        """            if (credential == null) {
              credential = new SecurityLocalCredentialEntity();
              credential.setPrincipalId(request.subjectPrincipalId());
              credential.setSecurityVersion(1);
              session.persist(credential);
            } else {
              credential.setSecurityVersion(nextVersion(credential.getSecurityVersion()));
            }
            credential.setPasswordAlgorithm(passwordHash.algorithm());
            credential.setPasswordVersion(passwordHash.version());
            credential.setPasswordHash(passwordHash.encodedHash());
            credential.setChangedAt(now);
            credential.setFailedAttemptCount(0);
            credential.setLockedUntil(null);
            record(
""",
        """            boolean newCredential = credential == null;
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
""",
    ),
    (
        """    try {
      AuthenticationOutcome outcome =
          sessionFactory.fromTransaction(
              session -> authenticatePassword(session, loginName, password, trace));
      return requireAuthenticated(outcome);
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
""",
        """    AuthenticationOutcome outcome;
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
""",
    ),
    (
        """    try {
      AuthenticationOutcome outcome =
          sessionFactory.fromTransaction(
              session -> authenticateAccessToken(session, tokenValue, prefix, trace));
      return requireAuthenticated(outcome);
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
""",
        """    AuthenticationOutcome outcome;
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
""",
    ),
    (
        """    if (permissionScopes.contains(null)) {
""",
        """    if (permissionScopes.stream().anyMatch(Objects::isNull)) {
""",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one replacement match, found {count}")
    text = text.replace(old, new)

path.write_text(text, encoding="utf-8")
