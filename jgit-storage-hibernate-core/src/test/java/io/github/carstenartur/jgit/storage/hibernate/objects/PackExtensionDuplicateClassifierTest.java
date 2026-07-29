/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class PackExtensionDuplicateClassifierTest {

  @Test
  void recognizesPortableUniqueViolationSqlStateThroughCauseChain() throws Exception {
    SQLException duplicate = new SQLException("duplicate", "23505", 0);
    assertTrue(isDuplicatePackExtension(new IllegalStateException("wrapper", duplicate)));
  }

  @Test
  void recognizesBothSqlServerDuplicateKeyCodes() throws Exception {
    assertTrue(isDuplicateSqlException(new SQLException("duplicate index", null, 2601)));
    assertTrue(isDuplicateSqlException(new SQLException("duplicate constraint", null, 2627)));
  }

  @Test
  void recognizesConstraintNameInSqlMessageAndNextException() throws Exception {
    SQLException primary = new SQLException("outer", "HY000", 0);
    primary.setNextException(
        new SQLException("Violation of UK_PACK_REPO_NAME_EXT", "HY000", 0));
    assertTrue(isDuplicateSqlException(primary));
  }

  @Test
  void rejectsUnrelatedSqlFailuresAndNull() throws Exception {
    assertFalse(isDuplicateSqlException(new SQLException("syntax", "42000", 0)));
    assertFalse(isDuplicateSqlException(null));
    assertFalse(isDuplicatePackExtension(new IllegalStateException("not a JDBC failure")));
  }

  private static boolean isDuplicatePackExtension(Throwable failure) throws Exception {
    return invoke(
        "isDuplicatePackExtension", new Class<?>[] {Throwable.class}, new Object[] {failure});
  }

  private static boolean isDuplicateSqlException(SQLException failure) throws Exception {
    return invoke(
        "isDuplicateSqlException", new Class<?>[] {SQLException.class}, new Object[] {failure});
  }

  private static boolean invoke(String name, Class<?>[] parameterTypes, Object[] arguments)
      throws Exception {
    Method method = StagedPackExtensionStore.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    try {
      return (boolean) method.invoke(null, arguments);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception checked) {
        throw checked;
      }
      throw exception;
    }
  }
}
