/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

/** Thrown when persisted policy cannot be evaluated deterministically. */
public final class SecurityPolicyConfigurationException extends IllegalArgumentException {

  /** Creates an exception with one stable diagnostic message. */
  public SecurityPolicyConfigurationException(String message) {
    super(message);
  }
}
