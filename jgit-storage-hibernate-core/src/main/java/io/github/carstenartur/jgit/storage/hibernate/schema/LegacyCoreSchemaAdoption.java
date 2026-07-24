/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.schema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates the pre-library Sandbox/Taxonomy schema before the adoption migration is run.
 *
 * <p>The validator is intentionally separate from Flyway SQL. It performs read-only checks before
 * any DDL is executed, so duplicate pack identities, incomplete rows, overlong pack extensions and
 * partially migrated schemas fail without silently deleting or overwriting stored Git data.
 */
public final class LegacyCoreSchemaAdoption {

  private static final int CORE_PACK_EXTENSION_LENGTH = 32;

  private static final Set<String> REQUIRED_LEGACY_COLUMNS =
      Set.of(
          "ID",
          "REPOSITORY_NAME",
          "PACK_NAME",
          "PACK_EXTENSION",
          "DATA",
          "FILE_SIZE",
          "CREATED_AT");

  /**
   * Inspect the existing {@code git_packs} table without changing it.
   *
   * @param connection connection using the schema to inspect
   * @return schema report
   * @throws LegacyCoreSchemaAdoptionException if the table cannot be inspected
   */
  public static LegacySchemaReport inspect(Connection connection) {
    try {
      Set<String> columns = readColumns(connection);
      Set<String> missingColumns = new TreeSet<>(REQUIRED_LEGACY_COLUMNS);
      missingColumns.removeAll(columns);

      boolean hasCommitted = columns.contains("COMMITTED");
      boolean hasCommittedAt = columns.contains("COMMITTED_AT");
      long packRows = count(connection, "select count(*) from git_packs");
      if (!missingColumns.isEmpty()) {
        return new LegacySchemaReport(
            Set.copyOf(columns),
            Set.copyOf(missingColumns),
            packRows,
            0,
            List.of(),
            hasCommitted,
            hasCommittedAt);
      }

      long incompletePackRows =
          count(
              connection,
              "select count(*) from git_packs "
                  + "where repository_name is null or pack_name is null "
                  + "or pack_extension is null or data is null "
                  + "or file_size < 0 or created_at is null");

      return new LegacySchemaReport(
          Set.copyOf(columns),
          Set.copyOf(missingColumns),
          packRows,
          incompletePackRows,
          findDuplicates(connection),
          hasCommitted,
          hasCommittedAt);
    } catch (SQLException exception) {
      throw new LegacyCoreSchemaAdoptionException(
          "Could not inspect the existing git_packs schema", exception);
    }
  }

  /**
   * Require that the existing table is either the exact pre-library shape or already adopted.
   *
   * <p>For the pre-library shape, every row must be complete, every pack extension must fit the
   * released Core column contract and every logical pack identity must be unique. Call this method
   * before running the migration at the matching legacy-adoption location.
   *
   * @param connection connection using the schema to validate
   * @return validated schema report
   * @throws LegacyCoreSchemaAdoptionException if adoption would be unsafe
   */
  public static LegacySchemaReport requireSafeToAdopt(Connection connection) {
    LegacySchemaReport report = inspect(connection);
    if (!report.missingRequiredColumns().isEmpty()) {
      throw new LegacyCoreSchemaAdoptionException(
          "git_packs is not the supported pre-library schema; missing columns: "
              + report.missingRequiredColumns());
    }
    if (report.hasCommittedColumn() != report.hasCommittedAtColumn()) {
      throw new LegacyCoreSchemaAdoptionException(
          "git_packs is only partially migrated: committed and committed_at must either both be "
              + "absent or both be present");
    }
    if (report.incompletePackRows() > 0) {
      throw new LegacyCoreSchemaAdoptionException(
          "git_packs contains "
              + report.incompletePackRows()
              + " incomplete rows; adoption did not modify the schema");
    }
    long overlongPackExtensionRows = countOverlongPackExtensionRows(connection);
    if (overlongPackExtensionRows > 0) {
      throw new LegacyCoreSchemaAdoptionException(
          "git_packs contains "
              + overlongPackExtensionRows
              + " pack_extension values longer than "
              + CORE_PACK_EXTENSION_LENGTH
              + " characters; adoption did not modify the schema");
    }
    if (!report.duplicatePackIdentities().isEmpty()) {
      throw new LegacyCoreSchemaAdoptionException(
          "git_packs contains duplicate (repository_name, pack_name, pack_extension) identities: "
              + report.duplicatePackIdentities()
              + "; resolve the duplicates explicitly before adoption");
    }
    return report;
  }

  private static Set<String> readColumns(Connection connection) throws SQLException {
    Set<String> columns = new TreeSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("select * from git_packs where 1 = 0")) {
      ResultSetMetaData metadata = resultSet.getMetaData();
      for (int index = 1; index <= metadata.getColumnCount(); index++) {
        columns.add(metadata.getColumnName(index).toUpperCase(Locale.ROOT));
      }
    }
    return columns;
  }

  private static long countOverlongPackExtensionRows(Connection connection) {
    try {
      return count(
          connection,
          "select count(*) from git_packs where character_length(pack_extension) > "
              + CORE_PACK_EXTENSION_LENGTH);
    } catch (SQLException exception) {
      throw new LegacyCoreSchemaAdoptionException(
          "Could not validate existing git_packs pack_extension values", exception);
    }
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private static List<DuplicatePackIdentity> findDuplicates(Connection connection)
      throws SQLException {
    List<DuplicatePackIdentity> duplicates = new ArrayList<>();
    String sql =
        "select repository_name, pack_name, pack_extension, count(*) "
            + "from git_packs group by repository_name, pack_name, pack_extension "
            + "having count(*) > 1 order by repository_name, pack_name, pack_extension";
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        duplicates.add(
            new DuplicatePackIdentity(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getLong(4)));
      }
    }
    return List.copyOf(duplicates);
  }

  /** Read-only report describing the existing {@code git_packs} table. */
  public record LegacySchemaReport(
      Set<String> columns,
      Set<String> missingRequiredColumns,
      long packRows,
      long incompletePackRows,
      List<DuplicatePackIdentity> duplicatePackIdentities,
      boolean hasCommittedColumn,
      boolean hasCommittedAtColumn) {

    /**
     * Return whether the table still needs the one-time adoption migration.
     *
     * @return {@code true} only for the exact pre-library column shape
     */
    public boolean requiresAdoption() {
      return !hasCommittedColumn && !hasCommittedAtColumn;
    }
  }

  /** One duplicate logical pack identity found during the read-only preflight. */
  public record DuplicatePackIdentity(
      String repositoryName, String packName, String packExtension, long rowCount) {}

  private LegacyCoreSchemaAdoption() {}
}
