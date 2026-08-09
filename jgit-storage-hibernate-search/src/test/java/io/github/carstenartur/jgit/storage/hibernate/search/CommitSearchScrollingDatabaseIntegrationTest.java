/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchCursor;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

@Testcontainers(disabledWithoutDocker = true)
class CommitSearchScrollingDatabaseIntegrationTest {

  private static final String AUTHOR_EMAIL = "alice@example.com";
  private static final String COMMITTER_EMAIL = "release-bot@example.com";
  private static final String EXACT_PATH = "src/main/java/example/Needle.java";
  private static final Instant BASE_TIME = Instant.parse("2026-08-09T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("scrolling_contract")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void scrollingContractWorksOnPostgreSql() {
    verifyDatabase(
        new Database(
            "postgresql-scroll-contract",
            POSTGRESQL.getJdbcUrl(),
            POSTGRESQL.getUsername(),
            POSTGRESQL.getPassword(),
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect",
            CoreSchemaMigrations.POSTGRESQL_LOCATION,
            SearchSchemaMigrations.POSTGRESQL_LOCATION));
  }

  @Test
  void scrollingContractWorksOnSqlServer() {
    verifyDatabase(
        new Database(
            "sqlserver-scroll-contract",
            SQL_SERVER.getJdbcUrl(),
            SQL_SERVER.getUsername(),
            SQL_SERVER.getPassword(),
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "org.hibernate.dialect.SQLServerDialect",
            CoreSchemaMigrations.SQL_SERVER_LOCATION,
            SearchSchemaMigrations.SQL_SERVER_LOCATION));
  }

  private static void verifyDatabase(Database database) {
    migrate(database, database.coreLocation(), CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    migrate(database, database.searchLocation(), SearchSchemaMigrations.SCHEMA_HISTORY_TABLE);

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(
            database.hibernateProperties(), SearchEntities.annotatedClasses())) {
      persistProjections(provider, database.repositoryName());
      GitHistorySearchService service = new GitHistorySearchService(provider.getSessionFactory());

      CommitHistoryQuery structuredQuery = structuredQuery(database.repositoryName());
      verifyStructuredScrolling(service, structuredQuery);
      verifyIndexedScrolling(service, database.repositoryName());
      verifyEarlyCloseReleasesSession(provider, service, structuredQuery, database.repositoryName());
      verifyInterruptionClosesCursor(service, database.repositoryName());
      verifyOffsetAndChunkBounds(service, database.repositoryName());
    }
  }

  private static void verifyStructuredScrolling(
      GitHistorySearchService service, CommitHistoryQuery query) {
    List<String> objectIds = new ArrayList<>();
    try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, 2)) {
      List<CommitSearchHit> first = cursor.nextChunk();
      List<CommitSearchHit> second = cursor.nextChunk();
      assertEquals(List.of(objectId(1), objectId(2)), ids(first));
      assertEquals(List.of(objectId(3), objectId(4)), ids(second));
      first.forEach(hit -> objectIds.add(hit.objectId()));
      second.forEach(hit -> objectIds.add(hit.objectId()));
      assertTrue(cursor.nextChunk().isEmpty());
      assertTrue(cursor.isClosed());
    }
    assertEquals(
        List.of(objectId(1), objectId(2), objectId(3), objectId(4)), objectIds);
  }

  private static void verifyIndexedScrolling(
      GitHistorySearchService service, String repositoryName) {
    CommitHistoryQuery indexedQuery =
        CommitHistoryQuery.forRepository(repositoryName)
            .matchingText("needle")
            .touchingExactPath(EXACT_PATH)
            .authoredBy(AUTHOR_EMAIL)
            .committedBy(COMMITTER_EMAIL)
            .committedBetween(BASE_TIME.plusSeconds(400), BASE_TIME.plusSeconds(600))
            .limit(3)
            .build();

    try (CommitSearchCursor cursor = service.scrollChangeSummaries(indexedQuery, 2)) {
      assertEquals(List.of(objectId(1), objectId(2)), ids(cursor.nextChunk()));
      assertEquals(List.of(objectId(3)), ids(cursor.nextChunk()));
      assertTrue(cursor.nextChunk().isEmpty());
      assertTrue(cursor.isClosed());
    }
  }

  private static void verifyEarlyCloseReleasesSession(
      HibernateSessionFactoryProvider provider,
      GitHistorySearchService service,
      CommitHistoryQuery query,
      String repositoryName) {
    Statistics statistics = provider.getSessionFactory().getStatistics();
    long openedBefore = statistics.getSessionOpenCount();
    long closedBefore = statistics.getSessionCloseCount();

    CommitSearchCursor cursor = service.scrollChangeSummaries(query, 1);
    assertEquals(List.of(objectId(1)), ids(cursor.nextChunk()));
    cursor.close();

    assertTrue(cursor.isClosed());
    assertThrows(IllegalStateException.class, cursor::nextChunk);
    assertEquals(
        statistics.getSessionOpenCount() - openedBefore,
        statistics.getSessionCloseCount() - closedBefore,
        "Closing a database-backed cursor must release every Hibernate session it opened");
    assertEquals(6, service.countIndexedCommits(repositoryName));
  }

  private static void verifyInterruptionClosesCursor(
      GitHistorySearchService service, String repositoryName) {
    CommitHistoryQuery query =
        CommitHistoryQuery.forRepository(repositoryName)
            .matchingText("needle")
            .unbounded()
            .build();
    CommitSearchCursor cursor = service.scrollChangeSummaries(query, 2);
    try {
      Thread.currentThread().interrupt();
      assertThrows(CancellationException.class, cursor::nextChunk);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(cursor.isClosed());
    } finally {
      Thread.interrupted();
      cursor.close();
    }
  }

  private static void verifyOffsetAndChunkBounds(
      GitHistorySearchService service, String repositoryName) {
    assertEquals(2, service.maxOffset());

    CommitHistoryQuery acceptedPage =
        CommitHistoryQuery.forRepository(repositoryName).offset(2).limit(1).build();
    assertEquals(List.of(objectId(3)), ids(service.findChangeSummaries(acceptedPage)));

    CommitHistoryQuery deepPage =
        CommitHistoryQuery.forRepository(repositoryName).offset(3).limit(1).build();
    IllegalArgumentException deepFailure =
        assertThrows(IllegalArgumentException.class, () -> service.findChangeSummaries(deepPage));
    assertTrue(deepFailure.getMessage().contains("scrollChangeSummaries"));

    CommitHistoryQuery offsetScroll =
        CommitHistoryQuery.forRepository(repositoryName).offset(1).limit(1).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.scrollChangeSummaries(offsetScroll, 1));

    CommitHistoryQuery query =
        CommitHistoryQuery.forRepository(repositoryName).unbounded().build();
    assertThrows(IllegalArgumentException.class, () -> service.scrollChangeSummaries(query, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.scrollChangeSummaries(
                query, GitHistorySearchService.MAX_SCROLL_CHUNK_SIZE + 1));
  }

  private static CommitHistoryQuery structuredQuery(String repositoryName) {
    return CommitHistoryQuery.forRepository(repositoryName)
        .authoredBy(AUTHOR_EMAIL)
        .committedBy(COMMITTER_EMAIL)
        .touchingPath(EXACT_PATH)
        .committedBetween(BASE_TIME.plusSeconds(400), BASE_TIME.plusSeconds(600))
        .unbounded()
        .build();
  }

  private static List<String> ids(List<CommitSearchHit> hits) {
    return hits.stream().map(CommitSearchHit::objectId).toList();
  }

  private static void persistProjections(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      for (int i = 1; i <= 6; i++) {
        GitCommitIndex projection = new GitCommitIndex();
        projection.setRepositoryName(repositoryName);
        projection.setObjectId(objectId(i));
        projection.setShortMessage(i <= 4 ? "needle change" : "unrelated change");
        projection.setFullMessage(
            i <= 4 ? "needle change\n\nshared searchable body" : "unrelated change\n\nbody");
        projection.setAuthorName(i == 6 ? "Bob" : "Alice");
        projection.setAuthorEmail(i == 6 ? "bob@example.com" : AUTHOR_EMAIL);
        projection.setAuthorTime(commitTime(i));
        projection.setCommitterName("Release bot");
        projection.setCommitterEmail(COMMITTER_EMAIL);
        projection.setCommitterTime(commitTime(i));
        projection.setChangedPaths(i <= 4 || i == 6 ? EXACT_PATH : "docs/readme.md");
        projection.setChangedText(i <= 4 ? "needle changed line" : "unrelated changed line");
        session.persist(projection);
      }
      transaction.commit();
    }
  }

  private static Instant commitTime(int value) {
    return switch (value) {
      case 1, 2 -> BASE_TIME.plusSeconds(600);
      case 3 -> BASE_TIME.plusSeconds(500);
      case 4 -> BASE_TIME.plusSeconds(400);
      case 5 -> BASE_TIME.plusSeconds(300);
      default -> BASE_TIME.plusSeconds(200);
    };
  }

  private static String objectId(int value) {
    return String.format("%040d", value);
  }

  private static void migrate(Database database, String location, String historyTable) {
    Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(location)
        .table(historyTable)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate();
  }

  private record Database(
      String repositoryName,
      String url,
      String username,
      String password,
      String driver,
      String dialect,
      String coreLocation,
      String searchLocation) {

    Properties hibernateProperties() {
      Properties properties = new Properties();
      properties.put("hibernate.connection.url", url);
      properties.put("hibernate.connection.username", username);
      properties.put("hibernate.connection.password", password);
      properties.put("hibernate.connection.driver_class", driver);
      properties.put("hibernate.dialect", dialect);
      properties.put("hibernate.hbm2ddl.auto", "validate");
      properties.put("hibernate.show_sql", "false");
      properties.put("hibernate.generate_statistics", "true");
      properties.put("hibernate.search.backend.type", "lucene");
      properties.put("hibernate.search.backend.directory.type", "local-heap");
      properties.put(GitHistorySearchService.MAX_OFFSET_PROPERTY, "2");
      return properties;
    }
  }
}
