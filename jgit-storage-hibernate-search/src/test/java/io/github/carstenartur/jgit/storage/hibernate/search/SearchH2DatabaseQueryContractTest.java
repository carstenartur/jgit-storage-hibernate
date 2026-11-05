/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/** Executes the shared real-history query contract even when Docker is unavailable. */
class SearchH2DatabaseQueryContractTest extends SearchDatabaseQueryContract {
  private final String url =
      "jdbc:h2:mem:query_contract_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

  @Override
  protected void migrateSchemas() {
    migrate(CoreSchemaMigrations.H2_LOCATION, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    migrate(SearchSchemaMigrations.H2_LOCATION, SearchSchemaMigrations.SCHEMA_HISTORY_TABLE);
  }

  private void migrate(String location, String history) {
    Flyway.configure()
        .dataSource(url, "sa", "")
        .locations(location)
        .table(history)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate();
  }

  @Override
  protected Properties databaseProperties(Path luceneRoot) {
    var settings = new Properties();
    settings.put("hibernate.connection.url", url);
    settings.put("hibernate.connection.username", "sa");
    settings.put("hibernate.connection.password", "");
    settings.put("hibernate.hbm2ddl.auto", "validate");
    settings.put("hibernate.search.backend.type", "lucene");
    settings.put("hibernate.search.backend.directory.type", "local-filesystem");
    settings.put("hibernate.search.backend.directory.root", luceneRoot.toAbsolutePath().toString());
    return settings;
  }
}
