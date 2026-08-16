#!/usr/bin/env python3
from pathlib import Path

service_path = Path(
    "jgit-storage-hibernate-security/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/security/"
    "HibernateSecurityCredentialService.java"
)
policy_path = Path(
    "jgit-storage-hibernate-security/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/security/"
    "HibernateCredentialScopedRepositoryAccessPolicy.java"
)
test_path = Path(
    "jgit-storage-hibernate-security/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/security/"
    "HibernateCredentialScopedRepositoryAccessPolicyTest.java"
)

service = service_path.read_text(encoding="utf-8")
old = "  private static String serializeScopes(Set<GitRepositoryPermission> scopes) {\n"
new = "  static String serializeScopes(Set<GitRepositoryPermission> scopes) {\n"
if service.count(old) != 1:
    raise SystemExit("expected one credential-service serializeScopes declaration")
service_path.write_text(service.replace(old, new), encoding="utf-8")

policy = policy_path.read_text(encoding="utf-8")
replacements = [
    ("import java.util.stream.Collectors;\n", ""),
    (
        """      boolean scopesChanged =
          !canonicalScopes(access).equals(token.getPermissionScopes());
""",
        """      boolean scopesChanged =
          !HibernateSecurityCredentialService.serializeScopes(access.credentialScopes())
              .equals(token.getPermissionScopes());
""",
    ),
    (
        """      if (versionChanged || scopesChanged) {
        deny(
            access,
            request,
            SecurityAuthenticationReason.INVALID_CREDENTIALS,
            token.getSecurityVersion());
      }
""",
        """      if (versionChanged || scopesChanged) {
        deny(
            access,
            request,
            SecurityAuthenticationReason.INVALID_CREDENTIALS,
            token.getSecurityVersion());
        return;
      }
""",
    ),
    (
        """
  private static String canonicalScopes(AuthenticatedGitAccess access) {
    return access.credentialScopes().stream()
        .sorted()
        .map(Enum::name)
        .collect(Collectors.joining(","));
  }
""",
        "\n",
    ),
]
for old, new in replacements:
    count = policy.count(old)
    if count != 1:
        raise SystemExit(f"expected one policy replacement match, found {count}")
    policy = policy.replace(old, new)
policy_path.write_text(policy, encoding="utf-8")

test = test_path.read_text(encoding="utf-8")
old = """  @Test
  void tokenStoreFailureIsAuditedAndNeverDelegates() {
    HibernateSessionFactoryProvider provider = provider("store-failure");
    SessionFactory sessionFactory = provider.getSessionFactory();
    List<SecurityAccessAuditRecord> audit = new ArrayList<>();
    AtomicInteger delegated = new AtomicInteger();
    HibernateCredentialScopedRepositoryAccessPolicy policy =
        new HibernateCredentialScopedRepositoryAccessPolicy(
            sessionFactory,
            (context, request) -> delegated.incrementAndGet(),
            audit::add,
            CLOCK);
    sessionFactory.close();

    assertThrows(
        RuntimeException.class,
        () ->
            policy.require(
                tokenAccess("token-1", "alice", 1, Set.of(GitRepositoryPermission.READ)),
                READ_REQUEST));
    assertEquals(0, delegated.get());
    assertEquals(1, audit.size());
    assertEquals(SecurityAuditOutcome.FAILED, audit.getFirst().outcome());
    assertEquals("AUTHORIZATION_EVALUATION_FAILED", audit.getFirst().reasonCode());
    provider.close();
  }
"""
new = """  @Test
  void tokenStoreFailureIsAuditedAndNeverDelegates() {
    try (HibernateSessionFactoryProvider provider = provider("store-failure")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      List<SecurityAccessAuditRecord> audit = new ArrayList<>();
      AtomicInteger delegated = new AtomicInteger();
      HibernateCredentialScopedRepositoryAccessPolicy policy =
          new HibernateCredentialScopedRepositoryAccessPolicy(
              sessionFactory,
              (context, request) -> delegated.incrementAndGet(),
              audit::add,
              CLOCK);
      sessionFactory.close();

      assertThrows(
          RuntimeException.class,
          () ->
              policy.require(
                  tokenAccess("token-1", "alice", 1, Set.of(GitRepositoryPermission.READ)),
                  READ_REQUEST));
      assertEquals(0, delegated.get());
      assertEquals(1, audit.size());
      assertEquals(SecurityAuditOutcome.FAILED, audit.getFirst().outcome());
      assertEquals("AUTHORIZATION_EVALUATION_FAILED", audit.getFirst().reasonCode());
    }
  }
"""
if test.count(old) != 1:
    raise SystemExit("expected one token-store failure test")
test_path.write_text(test.replace(old, new), encoding="utf-8")
