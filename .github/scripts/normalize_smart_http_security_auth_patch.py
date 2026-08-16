#!/usr/bin/env python3
from pathlib import Path

path = Path('.github/scripts/smart_http_security_auth_patch.py')
text = path.read_text(encoding='utf-8')
replacements = [
    (
        '''    } catch (ServiceMayNotContinueException | ServiceNotAuthorizedException mapped) {
      throw mapped;
''',
        ''
    ),
    (
        '''      assertTrue(
          passwordHasher.lastVerified().stream()
              .flatMapToInt(value -> new String(value).chars())
              .allMatch(character -> character == 0));
''',
        '''      assertTrue(passwordHasher.lastVerifiedWasCleared());
'''
    ),
    (
        '''    List<char[]> lastVerified() {
      return List.of(lastVerified.get());
    }
''',
        '''    boolean lastVerifiedWasCleared() {
      char[] value = lastVerified.get();
      if (value == null) {
        return false;
      }
      for (char character : value) {
        if (character != '\\0') {
          return false;
        }
      }
      return true;
    }
'''
    ),
    (
        '''  void authenticationStoreFailureRemainsServerError() {
    HibernateSessionFactoryProvider provider = provider("failure");
    SessionFactory sessionFactory = provider.getSessionFactory();
    persistPrincipal(sessionFactory, "alice", "alice");
    HibernateSecurityCredentialService credentials =
        credentials(
            sessionFactory,
            new CapturingPasswordHasher(),
            new HibernateSecurityIdentityAuditService(sessionFactory));
    IssuedAccessToken token =
        credentials.issueAccessToken(
            SecurityManagementRequest.issueToken(ADMIN, "alice"),
            Set.of(GitRepositoryPermission.READ),
            Instant.now().plus(Duration.ofHours(1)));
    SecuritySmartHttpAccessContextProvider authentication =
        authentication(credentials, "failure");
    sessionFactory.close();

    ServiceMayNotContinueException failure =
        assertThrows(
            ServiceMayNotContinueException.class,
            () ->
                authentication.require(
                    request(true, "Bearer " + token.tokenValue())));
    assertEquals(500, failure.getStatusCode());
    assertFalse(String.valueOf(failure.getMessage()).contains(token.tokenValue()));
    provider.close();
  }
''',
        '''  void authenticationStoreFailureRemainsServerError() {
    try (HibernateSessionFactoryProvider provider = provider("failure")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      HibernateSecurityCredentialService credentials =
          credentials(
              sessionFactory,
              new CapturingPasswordHasher(),
              new HibernateSecurityIdentityAuditService(sessionFactory));
      IssuedAccessToken token =
          credentials.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              Instant.now().plus(Duration.ofHours(1)));
      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "failure");
      sessionFactory.close();

      ServiceMayNotContinueException failure =
          assertThrows(
              ServiceMayNotContinueException.class,
              () ->
                  authentication.require(
                      request(true, "Bearer " + token.tokenValue())));
      assertEquals(500, failure.getStatusCode());
      assertFalse(String.valueOf(failure.getMessage()).contains(token.tokenValue()));
    }
  }
'''
    ),
]
for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected one normalization match, found {count}')
    text = text.replace(old, new)
path.write_text(text, encoding='utf-8')
