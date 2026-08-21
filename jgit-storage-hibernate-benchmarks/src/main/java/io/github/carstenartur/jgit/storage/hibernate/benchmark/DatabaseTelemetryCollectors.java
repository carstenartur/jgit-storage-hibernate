/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.TreeMap;

/** Factory and JDBC implementations for benchmark-only native database telemetry. */
final class DatabaseTelemetryCollectors {

  static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.database-telemetry.enabled";
  static final String OUTPUT_PROPERTY =
      "jgit.storage.benchmark.database-telemetry.output";

  private DatabaseTelemetryCollectors() {}

  static DatabaseTelemetryCollector disabled(String backend, String reason) {
    return new DisabledCollector(backend, reason);
  }

  static DatabaseTelemetryCollector create(
      String backend, boolean enabled, String jdbcUrl, String username, String password) {
    if (!enabled) {
      return disabled(backend, "disabled-by-configuration");
    }
    ConnectionFactory connectionFactory =
        () -> DriverManager.getConnection(jdbcUrl, username, password);
    return switch (backend) {
      case "postgresql" -> new PostgreSqlCollector(connectionFactory);
      case "sqlserver" -> new SqlServerCollector(connectionFactory);
      default -> disabled(backend, "unsupported-backend");
    };
  }

  private record DisabledCollector(String backend, String reason)
      implements DatabaseTelemetryCollector {

    private DisabledCollector {
      Objects.requireNonNull(backend, "backend");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public boolean enabled() {
      return false;
    }

    @Override
    public DatabaseTelemetrySnapshot capture() {
      return DatabaseTelemetrySnapshot.disabled(backend, reason);
    }
  }

  @FunctionalInterface
  private interface ConnectionFactory {
    Connection open() throws SQLException;
  }

  @FunctionalInterface
  private interface ResultReader {
    void read(ResultSet result, SnapshotBuilder snapshot) throws SQLException;
  }

  private abstract static class JdbcCollector implements DatabaseTelemetryCollector {
    private final String backend;
    private final ConnectionFactory connectionFactory;

    private JdbcCollector(String backend, ConnectionFactory connectionFactory) {
      this.backend = backend;
      this.connectionFactory = connectionFactory;
    }

    @Override
    public final boolean enabled() {
      return true;
    }

    @Override
    public final DatabaseTelemetrySnapshot capture() {
      SnapshotBuilder snapshot = new SnapshotBuilder(backend);
      try (Connection connection = connectionFactory.open()) {
        connection.setReadOnly(true);
        connection.setAutoCommit(true);
        collect(connection, snapshot);
      } catch (SQLException failure) {
        snapshot.unsupported("collector.connection", sqlFailure(failure));
      }
      return snapshot.build();
    }

    abstract void collect(Connection connection, SnapshotBuilder snapshot);

    final void queryOne(
        Connection connection,
        SnapshotBuilder snapshot,
        String capability,
        String sql,
        ResultReader reader) {
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        if (!result.next()) {
          snapshot.unsupported(capability, "empty-result");
          return;
        }
        reader.read(result, snapshot);
      } catch (SQLException failure) {
        snapshot.unsupported(capability, sqlFailure(failure));
      }
    }

    final String sqlFailure(SQLException failure) {
      String state = failure.getSQLState();
      String normalizedState =
          state == null || state.isBlank()
              ? "unknown"
              : state.replaceAll("[^A-Za-z0-9]", "_");
      return "sql-state=" + normalizedState + ";vendor-code=" + failure.getErrorCode();
    }
  }

  private static final class PostgreSqlCollector extends JdbcCollector {

    private PostgreSqlCollector(ConnectionFactory connectionFactory) {
      super("postgresql", connectionFactory);
    }

    @Override
    void collect(Connection connection, SnapshotBuilder snapshot) {
      queryOne(
          connection,
          snapshot,
          "postgresql.settings",
          """
          SELECT current_setting('server_version') AS server_version,
                 current_setting('server_version_num') AS server_version_num,
                 current_setting('track_io_timing') AS track_io_timing,
                 COALESCE(current_setting('track_wal_io_timing', true), 'unavailable')
                   AS track_wal_io_timing,
                 current_setting('wal_sync_method') AS wal_sync_method,
                 current_setting('shared_buffers') AS shared_buffers
          """,
          (result, value) -> {
            value.serverVersion(result.getString("server_version"));
            value.metadata(
                "postgresql.server_version_num", result.getString("server_version_num"));
            value.metadata(
                "postgresql.track_io_timing", result.getString("track_io_timing"));
            value.metadata(
                "postgresql.track_wal_io_timing", result.getString("track_wal_io_timing"));
            value.metadata(
                "postgresql.wal_sync_method", result.getString("wal_sync_method"));
            value.metadata(
                "postgresql.shared_buffers", result.getString("shared_buffers"));
            value.metadata("postgresql.wait.scope", "cluster");
          });

      queryOne(
          connection,
          snapshot,
          "postgresql.pg_stat_wal",
          """
          SELECT wal_records,
                 wal_fpi,
                 wal_bytes::bigint AS wal_bytes,
                 wal_buffers_full,
                 wal_write,
                 wal_sync,
                 COALESCE(ROUND(wal_write_time * 1000), 0)::bigint
                   AS wal_write_time_micros,
                 COALESCE(ROUND(wal_sync_time * 1000), 0)::bigint
                   AS wal_sync_time_micros
          FROM pg_stat_wal
          """,
          (result, value) -> {
            value.counter("postgresql.wal.records", result.getLong("wal_records"));
            value.counter(
                "postgresql.wal.full_page_images", result.getLong("wal_fpi"));
            value.counter("postgresql.wal.bytes", result.getLong("wal_bytes"));
            value.counter(
                "postgresql.wal.buffers_full", result.getLong("wal_buffers_full"));
            value.counter("postgresql.wal.writes", result.getLong("wal_write"));
            value.counter("postgresql.wal.syncs", result.getLong("wal_sync"));
            value.counter(
                "postgresql.wal.write_time_micros",
                result.getLong("wal_write_time_micros"));
            value.counter(
                "postgresql.wal.sync_time_micros",
                result.getLong("wal_sync_time_micros"));
          });

      queryOne(
          connection,
          snapshot,
          "postgresql.pg_stat_database",
          """
          SELECT xact_commit,
                 xact_rollback,
                 blks_read,
                 blks_hit,
                 tup_returned,
                 tup_fetched,
                 tup_inserted,
                 tup_updated,
                 tup_deleted,
                 temp_files,
                 temp_bytes,
                 deadlocks,
                 COALESCE(checksum_failures, 0) AS checksum_failures,
                 COALESCE(ROUND(blk_read_time * 1000), 0)::bigint
                   AS blk_read_time_micros,
                 COALESCE(ROUND(blk_write_time * 1000), 0)::bigint
                   AS blk_write_time_micros
          FROM pg_stat_database
          WHERE datname = current_database()
          """,
          (result, value) -> {
            value.counter(
                "postgresql.database.commits", result.getLong("xact_commit"));
            value.counter(
                "postgresql.database.rollbacks", result.getLong("xact_rollback"));
            value.counter(
                "postgresql.database.blocks_read", result.getLong("blks_read"));
            value.counter(
                "postgresql.database.blocks_hit", result.getLong("blks_hit"));
            value.counter(
                "postgresql.database.tuples_returned", result.getLong("tup_returned"));
            value.counter(
                "postgresql.database.tuples_fetched", result.getLong("tup_fetched"));
            value.counter(
                "postgresql.database.tuples_inserted", result.getLong("tup_inserted"));
            value.counter(
                "postgresql.database.tuples_updated", result.getLong("tup_updated"));
            value.counter(
                "postgresql.database.tuples_deleted", result.getLong("tup_deleted"));
            value.counter(
                "postgresql.database.temp_files", result.getLong("temp_files"));
            value.counter(
                "postgresql.database.temp_bytes", result.getLong("temp_bytes"));
            value.counter(
                "postgresql.database.deadlocks", result.getLong("deadlocks"));
            value.counter(
                "postgresql.database.checksum_failures",
                result.getLong("checksum_failures"));
            value.counter(
                "postgresql.database.block_read_time_micros",
                result.getLong("blk_read_time_micros"));
            value.counter(
                "postgresql.database.block_write_time_micros",
                result.getLong("blk_write_time_micros"));
          });

      queryOne(
          connection,
          snapshot,
          "postgresql.pg_stat_io",
          """
          SELECT COALESCE(SUM(reads), 0)::bigint AS reads,
                 COALESCE(SUM(reads * op_bytes), 0)::bigint AS read_bytes,
                 COALESCE(ROUND(SUM(read_time) * 1000), 0)::bigint
                   AS read_time_micros,
                 COALESCE(SUM(writes), 0)::bigint AS writes,
                 COALESCE(SUM(writes * op_bytes), 0)::bigint AS write_bytes,
                 COALESCE(ROUND(SUM(write_time) * 1000), 0)::bigint
                   AS write_time_micros,
                 COALESCE(SUM(hits), 0)::bigint AS hits,
                 COALESCE(SUM(evictions), 0)::bigint AS evictions,
                 COALESCE(SUM(reuses), 0)::bigint AS reuses,
                 COALESCE(SUM(fsyncs), 0)::bigint AS fsyncs,
                 COALESCE(ROUND(SUM(fsync_time) * 1000), 0)::bigint
                   AS fsync_time_micros
          FROM pg_stat_io
          """,
          (result, value) -> {
            value.counter("postgresql.io.reads", result.getLong("reads"));
            value.counter(
                "postgresql.io.read_bytes", result.getLong("read_bytes"));
            value.counter(
                "postgresql.io.read_time_micros", result.getLong("read_time_micros"));
            value.counter("postgresql.io.writes", result.getLong("writes"));
            value.counter(
                "postgresql.io.write_bytes", result.getLong("write_bytes"));
            value.counter(
                "postgresql.io.write_time_micros",
                result.getLong("write_time_micros"));
            value.counter("postgresql.io.hits", result.getLong("hits"));
            value.counter(
                "postgresql.io.evictions", result.getLong("evictions"));
            value.counter("postgresql.io.reuses", result.getLong("reuses"));
            value.counter("postgresql.io.fsyncs", result.getLong("fsyncs"));
            value.counter(
                "postgresql.io.fsync_time_micros",
                result.getLong("fsync_time_micros"));
          });

      queryOne(
          connection,
          snapshot,
          "postgresql.waits",
          """
          SELECT COUNT(*)::bigint AS sessions,
                 COUNT(*) FILTER (WHERE wait_event_type = 'IO')::bigint AS io_waiters,
                 COUNT(*) FILTER (WHERE wait_event_type = 'Lock')::bigint AS lock_waiters,
                 COUNT(*) FILTER (WHERE state = 'active')::bigint AS active_sessions
          FROM pg_stat_activity
          WHERE datname = current_database()
          """,
          (result, value) -> {
            value.gauge(
                "postgresql.wait.sessions", result.getLong("sessions"));
            value.gauge(
                "postgresql.wait.io_waiters", result.getLong("io_waiters"));
            value.gauge(
                "postgresql.wait.lock_waiters", result.getLong("lock_waiters"));
            value.gauge(
                "postgresql.wait.active_sessions", result.getLong("active_sessions"));
          });

      collectPgStatStatements(connection, snapshot);
    }

    private void collectPgStatStatements(
        Connection connection, SnapshotBuilder snapshot) {
      try (Statement statement = connection.createStatement();
          ResultSet extension =
              statement.executeQuery(
                  "SELECT extversion FROM pg_extension WHERE extname = 'pg_stat_statements'")) {
        if (!extension.next()) {
          snapshot.unsupported(
              "postgresql.pg_stat_statements", "extension-not-installed");
          return;
        }
        snapshot.metadata(
            "postgresql.pg_stat_statements.version", extension.getString("extversion"));
      } catch (SQLException failure) {
        snapshot.unsupported(
            "postgresql.pg_stat_statements", sqlFailure(failure));
        return;
      }

      queryOne(
          connection,
          snapshot,
          "postgresql.pg_stat_statements",
          """
          SELECT COUNT(*)::bigint AS statements,
                 COALESCE(SUM(calls), 0)::bigint AS calls,
                 COALESCE(ROUND(SUM(total_exec_time) * 1000), 0)::bigint
                   AS total_exec_time_micros,
                 COALESCE(SUM(rows), 0)::bigint AS rows,
                 COALESCE(SUM(shared_blks_hit), 0)::bigint AS shared_blks_hit,
                 COALESCE(SUM(shared_blks_read), 0)::bigint AS shared_blks_read,
                 COALESCE(SUM(shared_blks_dirtied), 0)::bigint AS shared_blks_dirtied,
                 COALESCE(SUM(shared_blks_written), 0)::bigint AS shared_blks_written,
                 COALESCE(SUM(temp_blks_read), 0)::bigint AS temp_blks_read,
                 COALESCE(SUM(temp_blks_written), 0)::bigint AS temp_blks_written,
                 COALESCE(ROUND(SUM(wal_bytes)), 0)::bigint AS wal_bytes
          FROM pg_stat_statements
          WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
          """,
          (result, value) -> {
            value.counter(
                "postgresql.statements.entries", result.getLong("statements"));
            value.counter(
                "postgresql.statements.calls", result.getLong("calls"));
            value.counter(
                "postgresql.statements.exec_time_micros",
                result.getLong("total_exec_time_micros"));
            value.counter(
                "postgresql.statements.rows", result.getLong("rows"));
            value.counter(
                "postgresql.statements.shared_blocks_hit",
                result.getLong("shared_blks_hit"));
            value.counter(
                "postgresql.statements.shared_blocks_read",
                result.getLong("shared_blks_read"));
            value.counter(
                "postgresql.statements.shared_blocks_dirtied",
                result.getLong("shared_blks_dirtied"));
            value.counter(
                "postgresql.statements.shared_blocks_written",
                result.getLong("shared_blks_written"));
            value.counter(
                "postgresql.statements.temp_blocks_read",
                result.getLong("temp_blks_read"));
            value.counter(
                "postgresql.statements.temp_blocks_written",
                result.getLong("temp_blks_written"));
            value.counter(
                "postgresql.statements.wal_bytes", result.getLong("wal_bytes"));
          });
    }
  }

  private static final class SqlServerCollector extends JdbcCollector {

    private SqlServerCollector(ConnectionFactory connectionFactory) {
      super("sqlserver", connectionFactory);
    }

    @Override
    void collect(Connection connection, SnapshotBuilder snapshot) {
      queryOne(
          connection,
          snapshot,
          "sqlserver.settings",
          """
          SELECT CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(128)) AS product_version,
                 CAST(SERVERPROPERTY('ProductLevel') AS nvarchar(128)) AS product_level,
                 CAST(SERVERPROPERTY('Edition') AS nvarchar(128)) AS edition,
                 CAST(SERVERPROPERTY('EngineEdition') AS bigint) AS engine_edition
          """,
          (result, value) -> {
            value.serverVersion(result.getString("product_version"));
            value.metadata(
                "sqlserver.product_level", result.getString("product_level"));
            value.metadata(
                "sqlserver.edition", result.getString("edition"));
            value.metadata(
                "sqlserver.engine_edition",
                Long.toString(result.getLong("engine_edition")));
            value.metadata("sqlserver.wait.scope", "server");
          });

      queryOne(
          connection,
          snapshot,
          "sqlserver.io_virtual_file_stats",
          """
          SELECT COALESCE(SUM(CASE WHEN files.type = 0 THEN stats.num_of_reads ELSE 0 END), 0)
                   AS data_reads,
                 COALESCE(SUM(CASE WHEN files.type = 0 THEN stats.num_of_bytes_read ELSE 0 END), 0)
                   AS data_bytes_read,
                 COALESCE(SUM(CASE WHEN files.type = 0 THEN stats.io_stall_read_ms ELSE 0 END), 0)
                   AS data_read_stall_ms,
                 COALESCE(SUM(CASE WHEN files.type = 0 THEN stats.num_of_writes ELSE 0 END), 0)
                   AS data_writes,
                 COALESCE(SUM(CASE WHEN files.type = 0 THEN stats.num_of_bytes_written ELSE 0 END), 0)
                   AS data_bytes_written,
                 COALESCE(SUM(CASE WHEN files.type = 0 THEN stats.io_stall_write_ms ELSE 0 END), 0)
                   AS data_write_stall_ms,
                 COALESCE(SUM(CASE WHEN files.type = 1 THEN stats.num_of_reads ELSE 0 END), 0)
                   AS log_reads,
                 COALESCE(SUM(CASE WHEN files.type = 1 THEN stats.num_of_bytes_read ELSE 0 END), 0)
                   AS log_bytes_read,
                 COALESCE(SUM(CASE WHEN files.type = 1 THEN stats.io_stall_read_ms ELSE 0 END), 0)
                   AS log_read_stall_ms,
                 COALESCE(SUM(CASE WHEN files.type = 1 THEN stats.num_of_writes ELSE 0 END), 0)
                   AS log_writes,
                 COALESCE(SUM(CASE WHEN files.type = 1 THEN stats.num_of_bytes_written ELSE 0 END), 0)
                   AS log_bytes_written,
                 COALESCE(SUM(CASE WHEN files.type = 1 THEN stats.io_stall_write_ms ELSE 0 END), 0)
                   AS log_write_stall_ms
          FROM sys.dm_io_virtual_file_stats(DB_ID(), NULL) AS stats
          JOIN sys.database_files AS files ON files.file_id = stats.file_id
          """,
          (result, value) -> {
            value.counter(
                "sqlserver.io.data.reads", result.getLong("data_reads"));
            value.counter(
                "sqlserver.io.data.bytes_read", result.getLong("data_bytes_read"));
            value.counter(
                "sqlserver.io.data.read_stall_ms",
                result.getLong("data_read_stall_ms"));
            value.counter(
                "sqlserver.io.data.writes", result.getLong("data_writes"));
            value.counter(
                "sqlserver.io.data.bytes_written",
                result.getLong("data_bytes_written"));
            value.counter(
                "sqlserver.io.data.write_stall_ms",
                result.getLong("data_write_stall_ms"));
            value.counter(
                "sqlserver.io.log.reads", result.getLong("log_reads"));
            value.counter(
                "sqlserver.io.log.bytes_read", result.getLong("log_bytes_read"));
            value.counter(
                "sqlserver.io.log.read_stall_ms",
                result.getLong("log_read_stall_ms"));
            value.counter(
                "sqlserver.io.log.writes", result.getLong("log_writes"));
            value.counter(
                "sqlserver.io.log.bytes_written",
                result.getLong("log_bytes_written"));
            value.counter(
                "sqlserver.io.log.write_stall_ms",
                result.getLong("log_write_stall_ms"));
          });

      queryOne(
          connection,
          snapshot,
          "sqlserver.log_stats",
          """
          SELECT CAST(total_log_size_mb * 1048576.0 AS bigint) AS total_log_size_bytes,
                 CAST(active_log_size_mb * 1048576.0 AS bigint) AS active_log_size_bytes,
                 total_vlf_count,
                 active_vlf_count,
                 log_truncation_holdup_reason
          FROM sys.dm_db_log_stats(DB_ID())
          """,
          (result, value) -> {
            value.gauge(
                "sqlserver.log.total_size_bytes",
                result.getLong("total_log_size_bytes"));
            value.gauge(
                "sqlserver.log.active_size_bytes",
                result.getLong("active_log_size_bytes"));
            value.gauge(
                "sqlserver.log.total_vlf_count", result.getLong("total_vlf_count"));
            value.gauge(
                "sqlserver.log.active_vlf_count",
                result.getLong("active_vlf_count"));
            String reason = result.getString("log_truncation_holdup_reason");
            value.metadata(
                "sqlserver.log.truncation_holdup_reason",
                reason == null || reason.isBlank() ? "none" : reason);
          });

      queryOne(
          connection,
          snapshot,
          "sqlserver.wait_stats",
          """
          SELECT COALESCE(SUM(CASE WHEN wait_type = 'WRITELOG'
                                   THEN waiting_tasks_count ELSE 0 END), 0)
                   AS writelog_tasks,
                 COALESCE(SUM(CASE WHEN wait_type = 'WRITELOG'
                                   THEN wait_time_ms ELSE 0 END), 0)
                   AS writelog_wait_ms,
                 COALESCE(SUM(CASE WHEN wait_type LIKE 'PAGEIOLATCH[_]%'
                                     OR wait_type IN ('IO_COMPLETION', 'ASYNC_IO_COMPLETION')
                                   THEN waiting_tasks_count ELSE 0 END), 0)
                   AS page_io_tasks,
                 COALESCE(SUM(CASE WHEN wait_type LIKE 'PAGEIOLATCH[_]%'
                                     OR wait_type IN ('IO_COMPLETION', 'ASYNC_IO_COMPLETION')
                                   THEN wait_time_ms ELSE 0 END), 0)
                   AS page_io_wait_ms,
                 COALESCE(SUM(CASE WHEN wait_type LIKE 'LCK[_]%'
                                   THEN waiting_tasks_count ELSE 0 END), 0)
                   AS lock_tasks,
                 COALESCE(SUM(CASE WHEN wait_type LIKE 'LCK[_]%'
                                   THEN wait_time_ms ELSE 0 END), 0)
                   AS lock_wait_ms,
                 COALESCE(SUM(CASE WHEN wait_type = 'ASYNC_NETWORK_IO'
                                   THEN waiting_tasks_count ELSE 0 END), 0)
                   AS network_tasks,
                 COALESCE(SUM(CASE WHEN wait_type = 'ASYNC_NETWORK_IO'
                                   THEN wait_time_ms ELSE 0 END), 0)
                   AS network_wait_ms
          FROM sys.dm_os_wait_stats
          """,
          (result, value) -> {
            value.counter(
                "sqlserver.wait.writelog.tasks", result.getLong("writelog_tasks"));
            value.counter(
                "sqlserver.wait.writelog.time_ms",
                result.getLong("writelog_wait_ms"));
            value.counter(
                "sqlserver.wait.page_io.tasks", result.getLong("page_io_tasks"));
            value.counter(
                "sqlserver.wait.page_io.time_ms",
                result.getLong("page_io_wait_ms"));
            value.counter(
                "sqlserver.wait.lock.tasks", result.getLong("lock_tasks"));
            value.counter(
                "sqlserver.wait.lock.time_ms", result.getLong("lock_wait_ms"));
            value.counter(
                "sqlserver.wait.network.tasks", result.getLong("network_tasks"));
            value.counter(
                "sqlserver.wait.network.time_ms",
                result.getLong("network_wait_ms"));
          });

      queryOne(
          connection,
          snapshot,
          "sqlserver.index_usage",
          """
          SELECT COALESCE(SUM(user_seeks), 0) AS user_seeks,
                 COALESCE(SUM(user_scans), 0) AS user_scans,
                 COALESCE(SUM(user_lookups), 0) AS user_lookups,
                 COALESCE(SUM(user_updates), 0) AS user_updates
          FROM sys.dm_db_index_usage_stats
          WHERE database_id = DB_ID()
          """,
          (result, value) -> {
            value.counter(
                "sqlserver.index.user_seeks", result.getLong("user_seeks"));
            value.counter(
                "sqlserver.index.user_scans", result.getLong("user_scans"));
            value.counter(
                "sqlserver.index.user_lookups", result.getLong("user_lookups"));
            value.counter(
                "sqlserver.index.user_updates", result.getLong("user_updates"));
          });

      queryOne(
          connection,
          snapshot,
          "sqlserver.query_store",
          """
          SELECT actual_state_desc,
                 desired_state_desc,
                 CAST(current_storage_size_mb * 1048576.0 AS bigint)
                   AS current_storage_size_bytes,
                 CAST(max_storage_size_mb * 1048576.0 AS bigint)
                   AS max_storage_size_bytes,
                 readonly_reason
          FROM sys.database_query_store_options
          """,
          (result, value) -> {
            value.metadata(
                "sqlserver.query_store.actual_state",
                result.getString("actual_state_desc"));
            value.metadata(
                "sqlserver.query_store.desired_state",
                result.getString("desired_state_desc"));
            value.gauge(
                "sqlserver.query_store.current_storage_size_bytes",
                result.getLong("current_storage_size_bytes"));
            value.gauge(
                "sqlserver.query_store.max_storage_size_bytes",
                result.getLong("max_storage_size_bytes"));
            value.gauge(
                "sqlserver.query_store.readonly_reason",
                result.getLong("readonly_reason"));
          });
    }
  }

  private static final class SnapshotBuilder {
    private final String backend;
    private final Instant capturedAt = Instant.now();
    private final TreeMap<String, Long> counters = new TreeMap<>();
    private final TreeMap<String, Long> gauges = new TreeMap<>();
    private final TreeMap<String, String> metadata = new TreeMap<>();
    private final TreeMap<String, String> unsupported = new TreeMap<>();
    private String serverVersion = "unknown";

    private SnapshotBuilder(String backend) {
      this.backend = backend;
    }

    private void serverVersion(String value) {
      if (value != null && !value.isBlank()) {
        serverVersion = value;
      }
    }

    private void counter(String name, long value) {
      counters.put(name, value);
    }

    private void gauge(String name, long value) {
      gauges.put(name, value);
    }

    private void metadata(String name, String value) {
      if (value != null && !value.isBlank()) {
        metadata.put(name, value);
      }
    }

    private void unsupported(String capability, String reason) {
      unsupported.put(capability, reason);
    }

    private DatabaseTelemetrySnapshot build() {
      return new DatabaseTelemetrySnapshot(
          backend,
          true,
          capturedAt,
          serverVersion,
          counters,
          gauges,
          metadata,
          unsupported);
    }
  }
}
