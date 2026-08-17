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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.SecuritySchemaMigrationIntegrationTest.TestDatabase;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import java.util.Properties;

final class ExternalPrincipalBindingDatabaseContract {

  private ExternalPrincipalBindingDatabaseContract() {}

  static void verify(TestDatabase database) {
    ExternalPrincipalIdentity alice =
        ExternalPrincipalIdentity.of(
            "https://issuer.example.test/realms/taxonomy", "subject-alice-sso", "Alice");
    ExternalPrincipalIdentity bob =
        ExternalPrincipalIdentity.of(
            "https://issuer.example.test/realms/taxonomy", "subject-bob-sso", "Bob");

    try (HibernateSessionFactoryProvider provider = validatingProvider(database)) {
      HibernateExternalPrincipalBindingService service =
          new HibernateExternalPrincipalBindingService(
              provider.getSessionFactory(), identity -> "sso-" + identity.subject());
      assertEquals(
          ExternalPrincipalBindingOutcome.CREATED,
          service
              .resolve(alice, ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING)
              .outcome());
      assertEquals(
          ExternalPrincipalBindingOutcome.CREATED,
          service
              .resolve(bob, ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING)
              .outcome());
    }

    try (HibernateSessionFactoryProvider provider = validatingProvider(database)) {
      HibernateExternalPrincipalBindingService service =
          new HibernateExternalPrincipalBindingService(
              provider.getSessionFactory(), identity -> "must-not-run");
      ExternalPrincipalBindingResult aliceBinding =
          service.resolve(alice, ExternalPrincipalProvisioningPolicy.EXISTING_ONLY);
      ExternalPrincipalBindingResult bobBinding =
          service.resolve(bob, ExternalPrincipalProvisioningPolicy.EXISTING_ONLY);
      assertEquals("sso-subject-alice-sso", aliceBinding.principalId());
      assertEquals("sso-subject-bob-sso", bobBinding.principalId());
      assertEquals(ExternalPrincipalBindingOutcome.RESOLVED, aliceBinding.outcome());
      assertEquals(ExternalPrincipalBindingOutcome.RESOLVED, bobBinding.outcome());

      provider
          .getSessionFactory()
          .inTransaction(
              session -> {
                SecurityPrincipalEntity aliceEntity =
                    session.find(SecurityPrincipalEntity.class, aliceBinding.principalId());
                SecurityPrincipalEntity bobEntity =
                    session.find(SecurityPrincipalEntity.class, bobBinding.principalId());
                assertNull(aliceEntity.getLoginName());
                assertNull(bobEntity.getLoginName());
                assertEquals(alice.issuer(), aliceEntity.getExternalIssuer());
                assertEquals(bob.issuer(), bobEntity.getExternalIssuer());
              });
    }
  }

  private static HibernateSessionFactoryProvider validatingProvider(TestDatabase database) {
    Properties properties = database.hibernateProperties();
    properties.setProperty("hibernate.hbm2ddl.auto", "validate");
    properties.setProperty("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties, SecurityEntities.annotatedClasses());
  }
}
