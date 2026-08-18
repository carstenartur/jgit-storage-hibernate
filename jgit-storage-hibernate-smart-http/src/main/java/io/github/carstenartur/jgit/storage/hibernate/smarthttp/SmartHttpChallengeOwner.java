/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

/** Selects the single layer responsible for Smart HTTP authentication challenges. */
public enum SmartHttpChallengeOwner {
  /** Spring Security, the servlet container or another host layer owns all challenges. */
  APPLICATION,

  /** The application registers the filter produced by the routing provider. */
  LIBRARY
}
