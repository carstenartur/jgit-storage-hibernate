/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.CacheMode;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;

/**
 * Internal insertion strategy for immutable pack payload chunks.
 *
 * <p>The default stateful path preserves the established persistence-context batching behavior. The
 * experimental stateless path opens a child session that shares the parent session's JDBC
 * connection and resource-local transaction. The experimental direct JDBC path prepares portable
 * insert SQL from Hibernate's resolved table and column mapping, then executes it through the
 * parent session's JDBC coordinator. Only non-indexed raw chunk rows pass through this class;
 * searchable projections continue to use ordinary stateful sessions.
 */
final class HibernatePackChunkWriter implements AutoCloseable {

  static final String MODE_PROPERTY = "jgit.storage.hibernate.pack.chunk_writer";
  static final String STATEFUL_MODE = "stateful";
  static final String STATELESS_MODE = "stateless";
  static final String JDBC_MODE = "jdbc";

  private final Mode mode;
  private final Session statefulSession;
  private final StatelessSession statelessSession;
  private final SharedSessionContractImplementor jdbcSession;
  private final String jdbcInsertSql;

  private HibernatePackChunkWriter(
      Mode mode,
      Session statefulSession,
      StatelessSession statelessSession,
      SharedSessionContractImplementor jdbcSession,
      String jdbcInsertSql) {
    this.mode = mode;
    this.statefulSession = statefulSession;
    this.statelessSession = statelessSession;
    this.jdbcSession = jdbcSession;
    this.jdbcInsertSql = jdbcInsertSql;
  }

  /**
   * Open the configured writer for a parent session whose pending parent insert was flushed.
   *
   * <p>The stateless and JDBC paths clear the parent persistence context before writing chunks so
   * the parent session never manages the raw chunk instances. Calling {@code connection()} on the
   * stateless child, or using the parent session's JDBC coordinator directly, keeps parent, chunks
   * and any later publication mutation in the same resource-local transaction.
   */
  static HibernatePackChunkWriter open(Session parentSession) {
    Objects.requireNonNull(parentSession, "parentSession");
    Mode mode = configuredMode(parentSession);
    return switch (mode) {
      case STATEFUL ->
          new HibernatePackChunkWriter(mode, parentSession, null, null, null);
      case STATELESS -> {
        parentSession.clear();
        StatelessSession child =
            parentSession
                .statelessWithOptions()
                .connection()
                .initialCacheMode(CacheMode.IGNORE)
                .open();
        yield new HibernatePackChunkWriter(mode, null, child, null, null);
      }
      case JDBC -> {
        parentSession.clear();
        SharedSessionContractImplementor session =
            parentSession.unwrap(SharedSessionContractImplementor.class);
        yield new HibernatePackChunkWriter(
            mode, null, null, session, directJdbcInsertSql(session));
      }
    };
  }

  void insert(List<GitPackChunkEntity> chunks) {
    Objects.requireNonNull(chunks, "chunks");
    if (chunks.isEmpty()) {
      return;
    }
    switch (mode) {
      case STATELESS -> statelessSession.insertMultiple(List.copyOf(chunks));
      case JDBC -> insertJdbc(chunks);
      case STATEFUL -> {
        for (GitPackChunkEntity chunk : chunks) {
          statefulSession.persist(chunk);
        }
        statefulSession.flush();
        statefulSession.clear();
      }
    }
  }

  boolean stateless() {
    return mode == Mode.STATELESS;
  }

  boolean jdbc() {
    return mode == Mode.JDBC;
  }

  @Override
  public void close() {
    if (statelessSession != null) {
      statelessSession.close();
    }
  }

  private void insertJdbc(List<GitPackChunkEntity> chunks) {
    JdbcCoordinator jdbcCoordinator = jdbcSession.getJdbcCoordinator();
    PreparedStatement statement =
        jdbcCoordinator.getMutationStatementPreparer().prepareStatement(jdbcInsertSql, false);
    try {
      for (GitPackChunkEntity chunk : chunks) {
        statement.setLong(1, Objects.requireNonNull(chunk.getPackId(), "chunk.packId"));
        statement.setInt(2, chunk.getChunkIndex());
        statement.setBytes(3, Objects.requireNonNull(chunk.getData(), "chunk.data"));
        statement.setInt(4, chunk.getChunkSize());
        statement.addBatch();
      }

      int[] rowCounts;
      try {
        jdbcSession.getEventListenerManager().jdbcExecuteBatchStart();
        rowCounts = statement.executeBatch();
      } finally {
        jdbcSession.getEventListenerManager().jdbcExecuteBatchEnd();
      }
      verifyRowCounts(rowCounts, chunks.size());
    } catch (SQLException exception) {
      jdbcCoordinator.afterFailedStatementExecution(exception);
      throw jdbcSession
          .getJdbcServices()
          .getSqlExceptionHelper()
          .convert(exception, "Could not insert pack chunks through direct JDBC", jdbcInsertSql);
    } finally {
      jdbcCoordinator
          .getLogicalConnection()
          .getResourceRegistry()
          .release(statement);
      jdbcCoordinator.afterStatementExecution();
    }
  }

  private static void verifyRowCounts(int[] rowCounts, int expectedRows) {
    if (rowCounts.length != expectedRows) {
      throw new HibernateException(
          "Direct JDBC chunk batch returned "
              + rowCounts.length
              + " row counts for "
              + expectedRows
              + " inserts");
    }
    for (int index = 0; index < rowCounts.length; index++) {
      int rowCount = rowCounts[index];
      if (rowCount != 1 && rowCount != Statement.SUCCESS_NO_INFO) {
        throw new HibernateException(
            "Direct JDBC chunk insert " + index + " affected " + rowCount + " rows");
      }
    }
  }

  private static String directJdbcInsertSql(SharedSessionContractImplementor session) {
    EntityPersister persister =
        session
            .getFactory()
            .getMappingMetamodel()
            .getEntityDescriptor(GitPackChunkEntity.class);
    return "insert into "
        + persister.getTableName()
        + " ("
        + singleColumn(persister, "packId")
        + ", "
        + singleColumn(persister, "chunkIndex")
        + ", "
        + singleColumn(persister, "data")
        + ", "
        + singleColumn(persister, "chunkSize")
        + ") values (?, ?, ?, ?)";
  }

  private static String singleColumn(EntityPersister persister, String propertyName) {
    String[] columns = persister.getPropertyColumnNames(propertyName);
    if (columns.length != 1) {
      throw new IllegalStateException(
          "Expected one mapped column for GitPackChunkEntity."
              + propertyName
              + " but found "
              + columns.length);
    }
    return columns[0];
  }

  private static Mode configuredMode(Session session) {
    Object configured = session.getSessionFactory().getProperties().get(MODE_PROPERTY);
    if (configured == null || configured.toString().isBlank()) {
      return Mode.STATEFUL;
    }
    return switch (configured.toString().trim().toLowerCase(Locale.ROOT)) {
      case STATEFUL_MODE -> Mode.STATEFUL;
      case STATELESS_MODE -> Mode.STATELESS;
      case JDBC_MODE -> Mode.JDBC;
      default ->
          throw new IllegalArgumentException(
              "Unsupported "
                  + MODE_PROPERTY
                  + " value '"
                  + configured
                  + "'; expected '"
                  + STATEFUL_MODE
                  + "', '"
                  + STATELESS_MODE
                  + "' or '"
                  + JDBC_MODE
                  + "'");
    };
  }

  private enum Mode {
    STATEFUL,
    STATELESS,
    JDBC
  }
}
