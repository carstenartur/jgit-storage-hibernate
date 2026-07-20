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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.analysis.GitTextAnalysis;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurationContext;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurer;
import org.hibernate.search.engine.backend.analysis.AnalyzerNames;
import org.junit.jupiter.api.Test;

/** Verifies configurable natural-language analysis without stemming paths or source material. */
class ConfigurableGitTextAnalysisH2Test {

  private static final String REPOSITORY_NAME = "analysis-profile";
  private static final String MESSAGE_OBJECT_ID =
      "1111111111111111111111111111111111111111";
  private static final String MATERIAL_OBJECT_ID =
      "2222222222222222222222222222222222222222";

  @Test
  void defaultProfilePreservesNeutralStandardAnalysis() {
    Properties properties = h2Properties();
    assertEquals(GitTextAnalysis.DEFAULT_PROFILE_ID, GitTextAnalysis.profileId(properties));

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses())) {
      persist(provider, messageProjection(), materialProjection());
      GitHistorySearchService history =
          new GitHistorySearchService(provider.getSessionFactory());

      assertEquals(List.of(), objectIds(history.searchCommitText(REPOSITORY_NAME, "run", 10)));
      assertEquals(
          List.of(MESSAGE_OBJECT_ID, MATERIAL_OBJECT_ID),
          objectIds(history.searchCommitText(REPOSITORY_NAME, "running", 10)).stream()
              .sorted()
              .toList());
    }
  }

  @Test
  void customEnglishProfileStemsMessagesButNotPathsOrChangedText() {
    Properties properties = h2Properties();
    GitTextAnalysis.configure(properties, EnglishMessageAnalysisConfigurer.class, "english-snowball-v1");
    assertEquals("english-snowball-v1", GitTextAnalysis.profileId(properties));

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses())) {
      persist(provider, messageProjection(), materialProjection());
      GitHistorySearchService history =
          new GitHistorySearchService(provider.getSessionFactory());

      assertEquals(
          List.of(MESSAGE_OBJECT_ID),
          objectIds(history.searchCommitText(REPOSITORY_NAME, "run", 10)));
    }
  }

  private static List<String> objectIds(List<GitCommitIndex> hits) {
    return hits.stream().map(GitCommitIndex::getObjectId).toList();
  }

  private static GitCommitIndex messageProjection() {
    return projection(
        MESSAGE_OBJECT_ID,
        "Running calibration checks",
        "workflow.dsl",
        "stable source token",
        "message@example.org");
  }

  private static GitCommitIndex materialProjection() {
    return projection(
        MATERIAL_OBJECT_ID,
        "Record source token",
        "running/workflow.dsl",
        "running source identifier",
        "material@example.org");
  }

  private static GitCommitIndex projection(
      String objectId, String message, String changedPaths, String changedText, String authorEmail) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName(REPOSITORY_NAME);
    projection.setObjectId(objectId);
    projection.setShortMessage(message);
    projection.setFullMessage(message);
    projection.setAuthorName("Analysis Tester");
    projection.setAuthorEmail(authorEmail);
    projection.setCommitTime(Instant.parse("2026-07-20T00:00:00Z"));
    projection.setChangedPaths(changedPaths);
    projection.setChangedText(changedText);
    return projection;
  }

  private static void persist(
      HibernateSessionFactoryProvider provider, GitCommitIndex... projections) {
    try (Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      for (GitCommitIndex projection : projections) {
        session.persist(projection);
      }
      session.getTransaction().commit();
    }
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }

  /** Public class reference used by Hibernate Search's reflective bean resolver. */
  public static final class EnglishMessageAnalysisConfigurer
      implements LuceneAnalysisConfigurer {

    /** Required for reflective construction from a {@code class:} reference. */
    public EnglishMessageAnalysisConfigurer() {}

    @Override
    public void configure(LuceneAnalysisConfigurationContext context) {
      context
          .analyzer(AnalyzerNames.DEFAULT)
          .custom()
          .tokenizer("standard")
          .tokenFilter("lowercase")
          .tokenFilter("snowballPorter")
          .param("language", "English");
    }
  }
}
