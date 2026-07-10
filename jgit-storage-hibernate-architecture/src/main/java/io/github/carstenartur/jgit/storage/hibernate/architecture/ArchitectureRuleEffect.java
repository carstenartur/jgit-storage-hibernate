/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

/** Constraint semantics for a versioned architecture relation. */
public enum ArchitectureRuleEffect {
  ALLOW,
  FORBID,
  REQUIRE
}
