/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityLocalCredentialEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecurityCredentialPersistenceH2Test {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void persistsOnlyOneWayVerifiersAndNonSecretTokenMetadata() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistFixture(sessionFactory);

      sessionFactory.inTransaction(
          session -> {
            SecurityLocalCredentialEntity credential =
                session.find(SecurityLocalCredentialEntity.class, "alice");
            SecurityAccessTokenEntity token =
                session.find(SecurityAccessTokenEntity.class, "token-1");

            assertNotNull(credential);
            assertEquals("PBKDF2-HMAC-SHA256", credential.getPasswordAlgorithm());
            assertEquals("encoded-password-verifier", credential.getPasswordHash());
            assertEquals(2, credential.getFailedAttemptCount());
            assertEquals(NOW.plusSeconds(60), credential.getLockedUntil());
            assertEquals(4, credential.getSecurityVersion());

            assertNotNull(token);
            assertEquals("jsh_prefix", token.getTokenPrefix());
            assertEquals("HMAC-SHA256", token.getTokenAlgorithm());
            assertEquals("encoded-token-verifier", token.getTokenHash());
            assertEquals("READ,UPDATE_REF", token.getPermissionScopes());
            assertEquals(NOW, token.getIssuedAt());
            assertEquals(NOW.plusSeconds(3600), token.getExpiresAt());
            assertNull(token.getRevokedAt());
            assertEquals(5, token.getSecurityVersion());
          });

      Set<String> localCredentialColumns =
          columns(sessionFactory, "GIT_SECURITY_LOCAL_CREDENTIAL");
      Set<String> accessTokenColumns = columns(sessionFactory, "GIT_SECURITY_ACCESS_TOKEN");
      assertTrue(localCredentialColumns.contains("PASSWORD_HASH"));
      assertTrue(accessTokenColumns.contains("TOKEN_HASH"));
      assertTrue(accessTokenColumns.contains("TOKEN_PREFIX"));
      assertFalse(localCredentialColumns.contains("PASSWORD_VALUE"));
      assertFalse(accessTokenColumns.contains("TOKEN_VALUE"));
      assertFalse(accessTokenColumns.contains("PLAINTEXT_TOKEN"));
    }
  }

  private static void persistFixture(SessionFactory sessionFactory) {
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId("alice");
    principal.setPrincipalType(SecurityPrincipalType.USER);
    principal.setLoginName("alice");
    principal.setDisplayName("Alice");
    principal.setStatus(SecurityPrincipalStatus.ACTIVE);
    principal.setCreatedAt(NOW);
    principal.setUpdatedAt(NOW);
    principal.setSecurityVersion(1);

    SecurityLocalCredentialEntity credential = new SecurityLocalCredentialEntity();
    credential.setPrincipalId("alice");
    credential.setPasswordAlgorithm("PBKDF2-HMAC-SHA256");
    credential.setPasswordVersion(1);
    credential.setPasswordHash("encoded-password-verifier");
    credential.setChangedAt(NOW);
    credential.setFailedAttemptCount(2);
    credential.setLockedUntil(NOW.plusSeconds(60));
    credential.setSecurityVersion(4);

    SecurityAccessTokenEntity token = new SecurityAccessTokenEntity();
    token.setTokenId("token-1");
    token.setPrincipalId("alice");
    token.setTokenPrefix("jsh_prefix");
    token.setTokenAlgorithm("HMAC-SHA256");
    token.setTokenVersion(1);
    token.setTokenHash("encoded-token-verifier");
    token.setPermissionScopes("READ,UPDATE_REF");
    token.setIssuedAt(NOW);
    token.setExpiresAt(NOW.plusSeconds(3600));
    token.setLastUsedAt(null);
    token.setRevokedAt(null);
    token.setIssuedBy("admin");
    token.setSecurityVersion(5);

    sessionFactory.inTransaction(
        session -> {
          session.persist(principal);
          session.persist(credential);
          session.persist(token);
        });
  }

  private static Set<String> columns(SessionFactory sessionFactory, String tableName) {
    return sessionFactory.fromTransaction(
        session ->
            session.doReturningWork(
                connection -> {
                  Set<String> names = new HashSet<>();
                  try (ResultSet result =
                      connection.getMetaData().getColumns(null, null, tableName, null)) {
                    while (result.next()) {
                      names.add(result.getString("COLUMN_NAME").toUpperCase());
                    }
                  }
                  return names;
                }));
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:security-credential-persistence-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(
        properties, SecurityEntities.annotatedClasses());
  }
}
