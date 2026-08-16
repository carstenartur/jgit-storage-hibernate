#!/usr/bin/env python3
from pathlib import Path

service_path = Path(
    "jgit-storage-hibernate-security/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/security/"
    "HibernateSecurityCredentialService.java"
)
test_path = Path(
    "jgit-storage-hibernate-security/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/security/"
    "HibernateSecurityCredentialServiceTest.java"
)

service = service_path.read_text(encoding="utf-8")
service_replacements = [
    (
        """    token.setLastUsedAt(now);
    token.setSecurityVersion(nextVersion(token.getSecurityVersion()));
""",
        """    token.setLastUsedAt(now);
""",
    ),
    (
        """    return AuthenticationOutcome.denied(reason, retryAt);
""",
        """    return AuthenticationOutcome.denied(
        SecurityAuthenticationReason.INVALID_CREDENTIALS, null);
""",
    ),
    (
        """      SecurityAuthenticationException denied =
          new SecurityAuthenticationException(reason, retryAt, null);
""",
        """      SecurityAuthenticationException denied =
          new SecurityAuthenticationException(
              SecurityAuthenticationReason.INVALID_CREDENTIALS);
""",
    ),
]
for old, new in service_replacements:
    count = service.count(old)
    if count != 1:
        raise SystemExit(f"expected one service replacement match, found {count}")
    service = service.replace(old, new)
service_path.write_text(service, encoding="utf-8")

test = test_path.read_text(encoding="utf-8")
test_replacements = [
    (
        """      assertEquals(SecurityAuthenticationReason.PASSWORD_LOCKED, locked.reason());
      assertEquals(START.plus(Duration.ofMinutes(5)), locked.retryAt());
""",
        """      assertEquals(SecurityAuthenticationReason.INVALID_CREDENTIALS, locked.reason());
      assertNull(locked.retryAt());
""",
    ),
    (
        """      assertEquals(SecurityAuthenticationReason.PASSWORD_LOCKED, stillLocked.reason());
""",
        """      assertEquals(SecurityAuthenticationReason.INVALID_CREDENTIALS, stillLocked.reason());
      assertNull(stillLocked.retryAt());
""",
    ),
    (
        """      assertEquals(
          SecurityAuthenticationReason.CREDENTIAL_NOT_CONFIGURED,
          notConfigured.reason());
      assertTrue(audit.findBySubjectPrincipal("alice", 100).size() >= 10);
""",
        """      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          notConfigured.reason());
      assertNull(notConfigured.retryAt());
      assertTrue(
          audit.findBySubjectPrincipal("alice", 100).stream()
              .anyMatch(
                  event ->
                      SecurityAuthenticationReason.PASSWORD_LOCKED.name()
                          .equals(event.record().reasonCode())));
      assertTrue(
          audit.findBySubjectPrincipal("alice", 100).stream()
              .anyMatch(
                  event ->
                      SecurityAuthenticationReason.CREDENTIAL_NOT_CONFIGURED.name()
                          .equals(event.record().reasonCode())));
      assertTrue(audit.findBySubjectPrincipal("alice", 100).size() >= 10);
""",
    ),
    (
        """      assertEquals(
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticatePassword(
                          "disabled", chars("secret"), trace("disabled-login")))
              .reason());
""",
        """      assertEquals(
          SecurityAuthenticationReason.INVALID_CREDENTIALS,
          assertThrows(
                  SecurityAuthenticationException.class,
                  () ->
                      service.authenticatePassword(
                          "disabled", chars("secret"), trace("disabled-login")))
              .reason());
""",
    ),
    (
        """      assertTrue(used.securityVersion() > issued.metadata().securityVersion());
""",
        """      assertEquals(issued.metadata().securityVersion(), used.securityVersion());
""",
    ),
]
for old, new in test_replacements:
    count = test.count(old)
    if count != 1:
        raise SystemExit(f"expected one test replacement match, found {count}")
    test = test.replace(old, new)
test_path.write_text(test, encoding="utf-8")
