/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

class ReflogReferenceKeyH2Test {

  @Test
  void completeRefPredicateSeparatesLongNamesWithTheSameIndexedPrefix() throws Exception {
    String commonPrefix = "refs/heads/" + "a".repeat(GitReflogEntity.REF_NAME_KEY_LENGTH);
    String firstRef = commonPrefix + "-first";
    String secondRef = commonPrefix + "-second";
    assertEquals(
        GitReflogEntity.refNameKey(firstRef), GitReflogEntity.refNameKey(secondRef));
    assertNotEquals(firstRef, secondRef);

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties())) {
      HibernateReflogWriter writer =
          new HibernateReflogWriter(provider.getSessionFactory(), "long-ref-repository");
      PersonIdent actor =
          new PersonIdent(
              "Reflog benchmark",
              "reflog@example.invalid",
              Instant.parse("2026-08-08T00:00:00Z"),
              ZoneOffset.UTC);
      ObjectId firstId = ObjectId.fromString("1111111111111111111111111111111111111111");
      ObjectId secondId = ObjectId.fromString("2222222222222222222222222222222222222222");

      writer.log(firstRef, ObjectId.zeroId(), firstId, actor, "first long ref");
      writer.log(secondRef, ObjectId.zeroId(), secondId, actor, "second long ref");

      var first =
          new HibernateReflogReader(
                  provider.getSessionFactory(), "long-ref-repository", firstRef)
              .getLastEntry();
      var second =
          new HibernateReflogReader(
                  provider.getSessionFactory(), "long-ref-repository", secondRef)
              .getLastEntry();

      assertEquals(firstId, first.getNewId());
      assertEquals("first long ref", first.getComment());
      assertEquals(secondId, second.getNewId());
      assertEquals("second long ref", second.getComment());
    }
  }

  private static Properties properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:reflog-reference-key-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
