/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp.security;

/** Local Security credential schemes accepted from an HTTP Authorization header. */
public enum SecuritySmartHttpAuthenticationMethod {
  /** RFC 7617-style username/password credentials encoded as UTF-8. */
  BASIC,

  /** A one-way Security access token supplied as a bearer credential. */
  BEARER
}
