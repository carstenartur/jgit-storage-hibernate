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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.analysis.GitTextAnalysis;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that an on-disk Lucene index written with the released numeric document identifier can be
 * loaded after the ORM identifier changes to an assigned projection key.
 */
class PersistentNumericDocumentIdCompatibilityH2Test {

  private static final String FIRST_REPOSITORY = "persistent-id-first";
  private static final String SECOND_REPOSITORY = "persistent-id-second";

  @TempDir Path temporaryDirectory;

  @Test
  void numericDocumentsRemainLoadableProjectableDeletableAndRestartable() throws Exception {
    Path database = temporaryDirectory.resolve("database/search");
    Path lucene = temporaryDirectory.resolve("lucene");

    try (HibernateSessionFactoryProvider legacy = legacyProvider(database, lucene)) {
      persistLegacy(
          legacy,
          FIRST_REPOSITORY,
          "1111111111111111111111111111111111111111",
          "alpha compatibility");
      persistLegacy(
          legacy,
          SECOND_REPOSITORY,
          "2222222222222222222222222222222222222222",
          "beta compatibility");
    }

    applyCurrentProfileMigration(database);

    try (HibernateSessionFactoryProvider upgraded = upgradedProvider(database, lucene)) {
      GitHistorySearchService search = new GitHistorySearchService(upgraded.getSessionFactory());

      List<GitCommitIndex> entityHits = search.searchCommitText(FIRST_REPOSITORY, "alpha", 10);
      assertEquals(1, entityHits.size());
      GitCommitIndex first = entityHits.getFirst();
      assertEquals("1111111111111111111111111111111111111111", first.getObjectId());
      assertNotNull(first.getId(), "The historical numeric document identifier must remain mapped");
      assertNotNull(
          first.getProjectionKey(), "The assigned ORM identifier must be available after upgrade");
      assertEquals(SearchIndexingProfile.CONTENT.id(), first.getIndexProfile());

      List<CommitSearchHit> projectedHits =
          search.searchCommitTextSummaries(SECOND_REPOSITORY, "beta", 10);
      assertEquals(1, projectedHits.size());
      assertEquals(
          "2222222222222222222222222222222222222222",
          projectedHits.getFirst().objectId());

      try (Session session = upgraded.getSessionFactory().openSession()) {
        Transaction transaction = session.beginTransaction();
        GitCommitIndex managed =
            session
                .createQuery(
                    "FROM GitCommitIndex c WHERE c.repositoryName = :repo "
                        + "AND c.objectId = :objectId",
                    GitCommitIndex.class)
                .setParameter("repo", FIRST_REPOSITORY)
                .setParameter("objectId", first.getObjectId())
                .getSingleResult();
        session.remove(managed);
        transaction.commit();
      }

      assertEquals(List.of(), search.searchCommitText(FIRST_REPOSITORY, "alpha", 10));
      assertEquals(1, search.searchCommitText(SECOND_REPOSITORY, "beta", 10).size());
    }

    try (HibernateSessionFactoryProvider restarted = upgradedProvider(database, lucene)) {
      GitHistorySearchService search = new GitHistorySearchService(restarted.getSessionFactory());
      assertEquals(List.of(), search.searchCommitText(FIRST_REPOSITORY, "alpha", 10));
      assertEquals(1, search.searchCommitText(SECOND_REPOSITORY, "beta", 10).size());
    }
  }

  private static void persistLegacy(
      HibernateSessionFactoryProvider provider,
      String repository,
      String objectId,
      String message) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      LegacyCommitProjection projection = new LegacyCommitProjection();
      projection.projectionKey = "legacy-fixture-" + objectId;
      projection.repositoryName = repository;
      projection.objectId = objectId;
      projection.shortMessage = message;
      projection.fullMessage = message;
      projection.authorName = "Legacy author";
      projection.authorEmail = "legacy@example.invalid";
      projection.authorTime = Instant.parse("2026-01-01T10:00:00Z");
      projection.committerName = "Legacy committer";
      projection.committerEmail = "legacy@example.invalid";
      projection.committerTime = Instant.parse("2026-01-01T11:00:00Z");
      projection.changedPaths = "legacy/path.txt";
      projection.changedText = message;
      session.persist(projection);
      transaction.commit();
      assertNotNull(projection.id);
    }
  }

  /**
   * This test creates its historical schema through Hibernate rather than Flyway so that it can also
   * create the old Lucene mapping. Apply only the relational profile migration here; the real Flyway
   * scripts are covered independently by {@link SearchSchemaMigrationIntegrationTest} and the
   * database-specific migration tests.
   */
  private static void applyCurrentProfileMigration(Path database) throws Exception {
    try (Connection connection = DriverManager.getConnection(databaseUrl(database));
        Statement statement = connection.createStatement()) {
      statement.execute("alter table git_commit_index add column index_profile varchar(32)");
      statement.execute(
          "update git_commit_index set index_profile = 'content-v1' where index_profile is null");
      statement.execute(
          "alter table git_commit_index alter column index_profile set not null");
    }
  }

  private static HibernateSessionFactoryProvider legacyProvider(Path database, Path lucene) {
    Properties properties = properties(database, lucene);
    properties.put("hibernate.hbm2ddl.auto", "create");
    return new HibernateSessionFactoryProvider(properties, Set.of(LegacyCommitProjection.class));
  }

  private static HibernateSessionFactoryProvider upgradedProvider(Path database, Path lucene) {
    Properties properties = properties(database, lucene);
    properties.put("hibernate.hbm2ddl.auto", "validate");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }

  private static Properties properties(Path database, Path lucene) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", databaseUrl(database));
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-filesystem");
    properties.put("hibernate.search.backend.directory.root", lucene.toAbsolutePath().toString());
    properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
    return properties;
  }

  private static String databaseUrl(Path database) {
    return "jdbc:h2:file:" + database.toAbsolutePath() + ";AUTO_SERVER=FALSE";
  }

  /** Released-style numeric ORM/document identifier on the new relational column set. */
  @Entity(name = "LegacyGitCommitIndex")
  @Indexed(index = "GitCommitIndex")
  @Table(
      name = "git_commit_index",
      indexes = {
        @Index(name = "idx_commit_repo", columnList = "repository_name"),
        @Index(name = "idx_commit_repo_time", columnList = "repository_name, commit_time"),
        @Index(name = "idx_commit_repo_author_time", columnList = "repository_name, author_time"),
        @Index(name = "idx_commit_repo_author", columnList = "repository_name, author_email"),
        @Index(name = "idx_commit_repo_committer", columnList = "repository_name, committer_email")
      },
      uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_commit_repo_object",
            columnNames = {"repository_name", "object_id"}),
        @UniqueConstraint(name = "uk_commit_projection_key", columnNames = "projection_key")
      })
  static class LegacyCommitProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "projection_key", nullable = false, length = 64)
    String projectionKey;

    @KeywordField
    @Nationalized
    @Column(name = "repository_name", nullable = false, length = 255)
    String repositoryName;

    @KeywordField(projectable = Projectable.YES, sortable = Sortable.YES)
    @Column(name = "object_id", nullable = false, length = 40)
    String objectId;

    @FullTextField(
        analyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER,
        searchAnalyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER,
        projectable = Projectable.YES)
    @Nationalized
    @Column(name = "short_message", length = 2048)
    String shortMessage;

    @FullTextField(
        analyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER,
        searchAnalyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER)
    @Nationalized
    @Column(name = "full_message", length = 8192)
    String fullMessage;

    @KeywordField(projectable = Projectable.YES)
    @Nationalized
    @Column(name = "author_name")
    String authorName;

    @KeywordField(projectable = Projectable.YES)
    @Nationalized
    @Column(name = "author_email")
    String authorEmail;

    @GenericField(projectable = Projectable.YES, sortable = Sortable.YES)
    @Column(name = "author_time")
    Instant authorTime;

    @KeywordField(projectable = Projectable.YES)
    @Nationalized
    @Column(name = "committer_name")
    String committerName;

    @KeywordField(projectable = Projectable.YES)
    @Nationalized
    @Column(name = "committer_email")
    String committerEmail;

    @GenericField(projectable = Projectable.YES, sortable = Sortable.YES)
    @Column(name = "commit_time")
    Instant committerTime;

    @FullTextField(analyzer = GitTextAnalysis.STRUCTURED_TEXT_ANALYZER)
    @Nationalized
    @Column(name = "changed_paths", length = 16384)
    String changedPaths;

    @FullTextField(analyzer = GitTextAnalysis.STRUCTURED_TEXT_ANALYZER)
    @Nationalized
    @Column(name = "changed_text", length = 262144)
    String changedText;
  }
}
