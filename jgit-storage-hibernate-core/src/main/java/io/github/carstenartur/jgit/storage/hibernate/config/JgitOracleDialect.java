/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.OracleServerConfiguration;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

/**
 * Oracle dialect with a capacity-safe nationalized String mapping for JGit storage.
 *
 * <p>Oracle's standard AL16UTF16 national character set supports at most 2000 NVARCHAR2 characters
 * with STANDARD string sizing. Longer nationalized Strings therefore need NCLOB, including Search's
 * 2048-character commit subject. Ordinary VARCHAR2 and other dialect mappings retain Hibernate's
 * defaults. This conservative nationalized limit also works with EXTENDED string sizing.
 *
 * <p>{@link HibernateSessionFactoryProvider} selects this dialect for standard Oracle configuration
 * and automatic Oracle detection. Applications building their own SessionFactory should select this
 * public dialect using {@code hibernate.dialect}.
 */
public class JgitOracleDialect extends OracleDialect {
  public JgitOracleDialect() {
    super();
  }

  public JgitOracleDialect(DatabaseVersion version) {
    super(version);
  }

  public JgitOracleDialect(DialectResolutionInfo info) {
    super(info);
  }

  public JgitOracleDialect(DialectResolutionInfo info, OracleServerConfiguration configuration) {
    super(info, configuration);
  }

  @Override
  public int getMaxNVarcharLength() {
    return 2000;
  }
}
