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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityVersionEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.schema.SecuritySchemaMigrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecuritySchemaMigrationIntegrationTest {

  private static final Instant FIXTURE_TIME = Instant.parse("2026-08-11T00:00:00Z");

  @Test
  void h2MigrationValidatesAndPersistsEverySecurityEntityAcrossRestart() throws Exception {
    try (TestDatabase database = h2Database()) {
      verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void hsqldbMigrationValidatesAndPersistsEverySecurityEntityAcrossRestart() throws Exception {
    try (TestDatabase database = hsqldbDatabase()) {
      verifyEmptyMigrationAndRestart(database);
    }
  }

  static void verifyEmptyMigrationAndRestart(TestDatabase database) throws Exception {
    migrate(
        database,
        database.coreLocation(),
        CoreSchemaMigrations.SCHEMA_HISTORY_TABLE,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION);
    migrate(
        database,
        database.securityLocation(),
        SecuritySchemaMigrations.SCHEMA_HISTORY_TABLE,
        SecuritySchemaMigrations.PRE_MIGRATION_BASELINE_VERSION);

    try (HibernateSessionFactoryProvider provider = validatingProvider(database)) {
      persistFixture(provider.getSessionFactory());
    }

    try (HibernateSessionFactoryProvider provider = validatingProvider(database)) {
      assertPersistedFixture(provider.getSessionFactory());
    }

    assertEquals(
        SecuritySchemaMigrations.CURRENT_SCHEMA_VERSION,
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations(database.securityLocation())
            .table(SecuritySchemaMigrations.SCHEMA_HISTORY_TABLE)
            .load()
            .info()
            .current()
            .getVersion()
            .getVersion());
  }

  private static TestDatabase h2Database() {
    String name = "security-phase-one-" + UUID.randomUUID();
    return new TestDatabase(
        "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
        "sa",
        "",
        "org.h2.Driver",
        "org.hibernate.dialect.H2Dialect",
        CoreSchemaMigrations.H2_LOCATION,
        SecuritySchemaMigrations.H2_LOCATION,
        () -> {});
  }

  private static TestDatabase hsqldbDatabase() {
    String name = "security_phase_one_" + UUID.randomUUID().toString().replace('-', '_');
    String url = "jdbc:hsqldb:mem:" + name;
    return new TestDatabase(
        url,
        "sa",
        "",
        "org.hsqldb.jdbc.JDBCDriver",
        "org.hibernate.dialect.HSQLDialect",
        CoreSchemaMigrations.HSQLDB_LOCATION,
        SecuritySchemaMigrations.HSQLDB_LOCATION,
        () -> {
          try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("shutdown");
          }
        });
  }

  private static HibernateSessionFactoryProvider validatingProvider(TestDatabase database) {
    Properties properties = database.hibernateProperties();
    properties.setProperty("hibernate.hbm2ddl.auto", "validate");
    properties.setProperty("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties, SecurityEntities.annotatedClasses());
  }

  private static void migrate(
      TestDatabase database, String location, String historyTable, String baselineVersion) {
    Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(location)
        .table(historyTable)
        .baselineOnMigrate(true)
        .baselineVersion(baselineVersion)
        .load()
        .migrate();
  }

  private static void persistFixture(SessionFactory sessionFactory) {
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId("alice");
    principal.setPrincipalType(SecurityPrincipalType.EXTERNAL);
    principal.setLoginName("alice@example.test");
    principal.setDisplayName("Alice");
    principal.setExternalIssuer("https://issuer.example.test");
    principal.setExternalSubject("subject-alice");
    principal.setStatus(SecurityPrincipalStatus.ACTIVE);
    principal.setCreatedAt(FIXTURE_TIME);
    principal.setUpdatedAt(FIXTURE_TIME);
    principal.setSecurityVersion(1);

    SecurityGroupEntity group = new SecurityGroupEntity();
    group.setGroupId("contributors");
    group.setGroupName("Contributors");
    group.setDescription("Can contribute to selected repositories");
    group.setStatus(SecurityGroupStatus.ACTIVE);
    group.setCreatedAt(FIXTURE_TIME);
    group.setUpdatedAt(FIXTURE_TIME);
    group.setSecurityVersion(2);

    SecurityGroupMembershipEntity membership = new SecurityGroupMembershipEntity();
    membership.setMembershipId("membership-1");
    membership.setGroupId("contributors");
    membership.setPrincipalId("alice");
    membership.setCreatedAt(FIXTURE_TIME);
    membership.setCreatedBy("system");
    membership.setSecurityVersion(3);

    SecurityRepositoryGrantEntity grant = new SecurityRepositoryGrantEntity();
    grant.setGrantId("grant-1");
    grant.setRepositoryName("workflows");
    grant.setSubjectType(SecuritySubjectType.GROUP);
    grant.setSubjectId("contributors");
    grant.setPermission(GitRepositoryPermission.UPDATE_REF);
    grant.setEffect(SecurityEffect.ALLOW);
    grant.setCreatedAt(FIXTURE_TIME);
    grant.setCreatedBy("system");
    grant.setSecurityVersion(4);

    SecurityRefRuleEntity rule = new SecurityRefRuleEntity();
    rule.setRuleId("rule-1");
    rule.setRepositoryName("workflows");
    rule.setRefPattern("refs/heads/integration/**");
    rule.setPermission(GitRepositoryPermission.FORCE_UPDATE);
    rule.setEffect(SecurityEffect.DENY);
    rule.setPriority(100);
    rule.setSubjectType(SecuritySubjectType.GROUP);
    rule.setSubjectId("contributors");
    rule.setEnabled(true);
    rule.setCreatedAt(FIXTURE_TIME);
    rule.setCreatedBy("system");
    rule.setSecurityVersion(5);

    SecurityVersionEntity version = new SecurityVersionEntity();
    version.setScopeKey("repository:workflows");
    version.setVersionValue(5);

    sessionFactory.inTransaction(
        session -> {
          session.persist(principal);
          session.persist(group);
          session.persist(membership);
          session.persist(grant);
          session.persist(rule);
          session.persist(version);
        });
  }

  private static void assertPersistedFixture(SessionFactory sessionFactory) {
    sessionFactory.inTransaction(
        session -> {
          SecurityPrincipalEntity principal =
              session.find(SecurityPrincipalEntity.class, "alice");
          assertNotNull(principal);
          assertEquals("alice", principal.getPrincipalId());
          assertEquals(SecurityPrincipalType.EXTERNAL, principal.getPrincipalType());
          assertEquals("alice@example.test", principal.getLoginName());
          assertEquals("Alice", principal.getDisplayName());
          assertEquals("https://issuer.example.test", principal.getExternalIssuer());
          assertEquals("subject-alice", principal.getExternalSubject());
          assertEquals(SecurityPrincipalStatus.ACTIVE, principal.getStatus());
          assertEquals(FIXTURE_TIME, principal.getCreatedAt());
          assertEquals(FIXTURE_TIME, principal.getUpdatedAt());
          assertTrue(principal.getEntityVersion() >= 0);
          assertEquals(1, principal.getSecurityVersion());

          SecurityGroupEntity group =
              session.find(SecurityGroupEntity.class, "contributors");
          assertNotNull(group);
          assertEquals("contributors", group.getGroupId());
          assertEquals("Contributors", group.getGroupName());
          assertEquals("Can contribute to selected repositories", group.getDescription());
          assertEquals(SecurityGroupStatus.ACTIVE, group.getStatus());
          assertEquals(FIXTURE_TIME, group.getCreatedAt());
          assertEquals(FIXTURE_TIME, group.getUpdatedAt());
          assertTrue(group.getEntityVersion() >= 0);
          assertEquals(2, group.getSecurityVersion());

          SecurityGroupMembershipEntity membership =
              session.find(SecurityGroupMembershipEntity.class, "membership-1");
          assertNotNull(membership);
          assertEquals("membership-1", membership.getMembershipId());
          assertEquals("contributors", membership.getGroupId());
          assertEquals("alice", membership.getPrincipalId());
          assertEquals(FIXTURE_TIME, membership.getCreatedAt());
          assertEquals("system", membership.getCreatedBy());
          assertEquals(3, membership.getSecurityVersion());

          SecurityRepositoryGrantEntity grant =
              session.find(SecurityRepositoryGrantEntity.class, "grant-1");
          assertNotNull(grant);
          assertEquals("grant-1", grant.getGrantId());
          assertEquals("workflows", grant.getRepositoryName());
          assertEquals(SecuritySubjectType.GROUP, grant.getSubjectType());
          assertEquals("contributors", grant.getSubjectId());
          assertEquals(GitRepositoryPermission.UPDATE_REF, grant.getPermission());
          assertEquals(SecurityEffect.ALLOW, grant.getEffect());
          assertEquals(FIXTURE_TIME, grant.getCreatedAt());
          assertEquals("system", grant.getCreatedBy());
          assertTrue(grant.getEntityVersion() >= 0);
          assertEquals(4, grant.getSecurityVersion());

          SecurityRefRuleEntity rule = session.find(SecurityRefRuleEntity.class, "rule-1");
          assertNotNull(rule);
          assertEquals("rule-1", rule.getRuleId());
          assertEquals("workflows", rule.getRepositoryName());
          assertEquals("refs/heads/integration/**", rule.getRefPattern());
          assertEquals(GitRepositoryPermission.FORCE_UPDATE, rule.getPermission());
          assertEquals(SecurityEffect.DENY, rule.getEffect());
          assertEquals(100, rule.getPriority());
          assertEquals(SecuritySubjectType.GROUP, rule.getSubjectType());
          assertEquals("contributors", rule.getSubjectId());
          assertTrue(rule.isEnabled());
          assertEquals(FIXTURE_TIME, rule.getCreatedAt());
          assertEquals("system", rule.getCreatedBy());
          assertTrue(rule.getEntityVersion() >= 0);
          assertEquals(5, rule.getSecurityVersion());

          SecurityVersionEntity version =
              session.find(SecurityVersionEntity.class, "repository:workflows");
          assertNotNull(version);
          assertEquals("repository:workflows", version.getScopeKey());
          assertEquals(5, version.getVersionValue());
          assertTrue(version.getEntityVersion() >= 0);
        });
  }

  record TestDatabase(
      String url,
      String username,
      String password,
      String driver,
      String dialect,
      String coreLocation,
      String securityLocation,
      CheckedRunnable closeAction)
      implements AutoCloseable {

    Properties hibernateProperties() {
      Properties properties = new Properties();
      properties.setProperty("hibernate.connection.driver_class", driver);
      properties.setProperty("hibernate.connection.url", url);
      properties.setProperty("hibernate.connection.username", username);
      properties.setProperty("hibernate.connection.password", password);
      properties.setProperty("hibernate.dialect", dialect);
      return properties;
    }

    @Override
    public void close() throws Exception {
      closeAction.run();
    }
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }
}
