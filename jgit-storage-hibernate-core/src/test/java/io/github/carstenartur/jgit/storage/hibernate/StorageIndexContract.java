/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class StorageIndexContract {

  private static final Set<String> REMOVED =
      Set.of(
          "IDX_PACK_REPO",
          "IDX_PACK_REPO_NAME",
          "IDX_PACK_CHUNK_PACK",
          "IDX_REFLOG_REPO",
          "IDX_REFLOG_REPO_REF");

  private StorageIndexContract() {}

  static void assertPortableOptimizedIndexes(Connection connection) throws Exception {
    Set<String> packIndexes = indexNames(connection, "git_packs");
    Set<String> chunkIndexes = indexNames(connection, "git_pack_chunks");
    Set<String> reflogIndexes = indexNames(connection, "git_reflog");
    Set<String> all = new HashSet<>();
    all.addAll(packIndexes);
    all.addAll(chunkIndexes);
    all.addAll(reflogIndexes);

    for (String removed : REMOVED) {
      assertFalse(all.contains(removed), () -> "redundant index remains: " + removed);
    }
    assertTrue(packIndexes.contains("IDX_PACK_REPO_COMMITTED"));
    assertTrue(packIndexes.contains("IDX_PACK_REPO_LEASE"));
    assertTrue(reflogIndexes.contains("IDX_REFLOG_REPO_REF_ID"));
  }

  static Set<String> indexNames(Connection connection, String tableName) throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    Set<String> names = new HashSet<>();
    collect(metadata, connection.getCatalog(), connection.getSchema(), tableName, names);
    if (names.isEmpty()) {
      collect(metadata, connection.getCatalog(), connection.getSchema(), tableName.toUpperCase(Locale.ROOT), names);
    }
    return Set.copyOf(names);
  }

  private static void collect(
      DatabaseMetaData metadata,
      String catalog,
      String schema,
      String tableName,
      Set<String> names)
      throws Exception {
    try (ResultSet resultSet = metadata.getIndexInfo(catalog, schema, tableName, false, false)) {
      while (resultSet.next()) {
        String indexName = resultSet.getString("INDEX_NAME");
        if (indexName != null) {
          names.add(indexName.toUpperCase(Locale.ROOT));
        }
      }
    }
  }
}
