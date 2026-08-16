/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable administrative operation for local credential and token lifecycle management. */
public enum SecurityManagementOperation {
  SET_PASSWORD,
  REMOVE_PASSWORD,
  UNLOCK_PASSWORD,
  ISSUE_ACCESS_TOKEN,
  REVOKE_ACCESS_TOKEN
}
