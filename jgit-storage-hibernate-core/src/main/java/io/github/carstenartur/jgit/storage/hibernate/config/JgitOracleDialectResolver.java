/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;

/** Oracle-only fallback used after application-configured dialect resolvers. */
public final class JgitOracleDialectResolver implements DialectResolver {
  @Override
  public Dialect resolveDialect(DialectResolutionInfo info) {
    return "Oracle".equals(info.getDatabaseName()) ? new JgitOracleDialect(info) : null;
  }
}
