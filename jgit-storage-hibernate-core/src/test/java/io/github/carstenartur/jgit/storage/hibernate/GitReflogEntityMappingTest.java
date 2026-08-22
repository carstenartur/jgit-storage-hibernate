/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import org.hibernate.annotations.Nationalized;
import org.junit.jupiter.api.Test;

class GitReflogEntityMappingTest {

  @Test
  void reflogMessageUsesOraclePortableNationalizedLength() throws Exception {
    Field message = GitReflogEntity.class.getDeclaredField("message");
    Column column = message.getAnnotation(Column.class);

    assertNotNull(message.getAnnotation(Nationalized.class));
    assertNotNull(column);
    assertEquals(2000, GitReflogEntity.MAX_MESSAGE_LENGTH);
    assertEquals(GitReflogEntity.MAX_MESSAGE_LENGTH, column.length());
  }

  @Test
  void deliveryIdIsNullableNationalizedAndPortablyBounded() throws Exception {
    Field deliveryId = GitReflogEntity.class.getDeclaredField("deliveryId");
    Column column = deliveryId.getAnnotation(Column.class);

    assertNotNull(deliveryId.getAnnotation(Nationalized.class));
    assertNotNull(column);
    assertTrue(column.nullable(), "legacy standalone reflogs require a nullable delivery ID");
    assertEquals(GitReflogEntity.MAX_DELIVERY_ID_LENGTH, column.length());
  }
}
