/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;

class OracleDialectConfigurationTest {
  @Test
  void standardOracleDialectUsesCapacitySafeNationalizedMapping() {
    for (Object configured : new Object[] {OracleDialect.class.getName(), OracleDialect.class}) {
      Properties properties = offlineProperties();
      properties.put("hibernate.dialect", configured);
      try (var provider = new HibernateSessionFactoryProvider(properties)) {
        Dialect dialect = dialect(provider);
        assertEquals(2000, dialect.getMaxNVarcharLength());
        assertEquals(4000, dialect.getMaxVarcharLength());
      }
      assertSame(configured, properties.get("hibernate.dialect"));
    }
  }

  @Test
  void explicitOracleDialectInstancePreservesVersionAndServerConfiguration() {
    var configured = new OracleDialect(org.hibernate.dialect.DatabaseVersion.make(23, 4));
    Properties properties = offlineProperties();
    properties.put("hibernate.dialect", configured);
    try (var provider = new HibernateSessionFactoryProvider(properties)) {
      assertSame(configured, dialect(provider));
      assertEquals(23, dialect(provider).getVersion().getMajor());
      assertEquals(java.sql.Types.BOOLEAN, dialect(provider).getPreferredSqlTypeCodeForBoolean());
    }
  }

  @Test
  void metadataBasedResolutionUsesCapacitySafeMapping() {
    Properties properties = offlineProperties();
    properties.put("jakarta.persistence.database-product-name", "Oracle");
    properties.put("jakarta.persistence.database-major-version", "23");
    properties.put("jakarta.persistence.database-minor-version", "0");
    try (var provider = new HibernateSessionFactoryProvider(properties)) {
      assertEquals(2000, dialect(provider).getMaxNVarcharLength());
      assertTrue(dialect(provider).getVersion().isSameOrAfter(23));
    }
  }

  @Test
  void customOracleDialectRemainsApplicationOwned() {
    var custom = new ApplicationOracleDialect();
    Properties properties = offlineProperties();
    properties.put("hibernate.dialect", custom);
    try (var provider = new HibernateSessionFactoryProvider(properties)) {
      assertSame(custom, dialect(provider));
      assertEquals(1234, dialect(provider).getMaxNVarcharLength());
    }
  }

  @Test
  void userDialectResolverKeepsPrecedenceAndOtherDatabasesKeepTheirDialect() {
    Properties properties = offlineProperties();
    properties.put("jakarta.persistence.database-product-name", "Oracle");
    properties.put("jakarta.persistence.database-major-version", "23");
    properties.put("hibernate.dialect_resolvers", ApplicationResolver.class.getName());
    try (var provider = new HibernateSessionFactoryProvider(properties)) {
      assertInstanceOf(ApplicationOracleDialect.class, dialect(provider));
    }
    assertEquals(
        ApplicationResolver.class.getName(), properties.get("hibernate.dialect_resolvers"));
    properties = offlineProperties();
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    try (var provider = new HibernateSessionFactoryProvider(properties)) {
      assertInstanceOf(org.hibernate.dialect.H2Dialect.class, dialect(provider));
    }
  }

  public static class ApplicationResolver
      implements org.hibernate.engine.jdbc.dialect.spi.DialectResolver {
    @Override
    public Dialect resolveDialect(
        org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo info) {
      return "Oracle".equals(info.getDatabaseName()) ? new ApplicationOracleDialect() : null;
    }
  }

  @Test
  void publicDialectPromotesOnlyOversizedNationalizedStringsToNclob() {
    Properties properties = offlineProperties();
    properties.put(
        "hibernate.dialect",
        "io.github.carstenartur.jgit.storage.hibernate.config.JgitOracleDialect");
    try (var provider =
        new HibernateSessionFactoryProvider(properties, java.util.List.of(CapacityEntity.class))) {
      var entity =
          provider
              .getSessionFactory()
              .unwrap(SessionFactoryImplementor.class)
              .getMappingMetamodel()
              .getEntityDescriptor(CapacityEntity.class);
      for (String name : java.util.List.of("subject", "message", "paths", "content")) {
        var attribute =
            (org.hibernate.metamodel.mapping.BasicValuedModelPart)
                entity.findAttributeMapping(name);
        assertEquals(
            java.sql.Types.NCLOB, attribute.getJdbcMapping().getJdbcType().getDdlTypeCode(), name);
      }
      var bounded =
          (org.hibernate.metamodel.mapping.BasicValuedModelPart)
              entity.findAttributeMapping("repository");
      assertEquals(
          java.sql.Types.NVARCHAR, bounded.getJdbcMapping().getJdbcType().getDdlTypeCode());
      var ordinary =
          (org.hibernate.metamodel.mapping.BasicValuedModelPart)
              entity.findAttributeMapping("ordinary");
      assertEquals(
          java.sql.Types.VARCHAR, ordinary.getJdbcMapping().getJdbcType().getDdlTypeCode());
    }
  }

  @jakarta.persistence.Entity(name = "OracleCapacityFixture")
  @jakarta.persistence.Table(name = "oracle_capacity_fixture")
  static class CapacityEntity {
    @jakarta.persistence.Id String id;

    @org.hibernate.annotations.Nationalized
    @jakarta.persistence.Column(length = 255)
    String repository;

    @org.hibernate.annotations.Nationalized
    @jakarta.persistence.Column(length = 2048)
    String subject;

    @org.hibernate.annotations.Nationalized
    @jakarta.persistence.Column(length = 8192)
    String message;

    @org.hibernate.annotations.Nationalized
    @jakarta.persistence.Column(length = 16384)
    String paths;

    @org.hibernate.annotations.Nationalized
    @jakarta.persistence.Column(length = 262144)
    String content;

    @jakarta.persistence.Column(length = 2048)
    String ordinary;
  }

  private static Properties offlineProperties() {
    var properties = new Properties();
    properties.put("hibernate.boot.allow_jdbc_metadata_access", "false");
    properties.put("hibernate.hbm2ddl.auto", "none");
    return properties;
  }

  private static Dialect dialect(HibernateSessionFactoryProvider provider) {
    return provider
        .getSessionFactory()
        .unwrap(SessionFactoryImplementor.class)
        .getJdbcServices()
        .getDialect();
  }

  public static class ApplicationOracleDialect extends OracleDialect {
    @Override
    public int getMaxNVarcharLength() {
      return 1234;
    }
  }
}
