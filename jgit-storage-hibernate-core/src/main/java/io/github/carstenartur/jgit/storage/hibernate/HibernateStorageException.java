/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Runtime exception raised by the Hibernate-backed storage facade. */
public class HibernateStorageException extends RuntimeException {

  /**
   * Create an exception with a message.
   *
   * @param message error message
   */
  public HibernateStorageException(String message) {
    super(message);
  }

  /**
   * Create an exception with a message and cause.
   *
   * @param message error message
   * @param cause root cause
   */
  public HibernateStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
