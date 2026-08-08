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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.search.service.SearchIndexProfileMismatchException;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class SearchIndexProfileDefaultCompatibilityH2Test {

  @Test
  void returningToImplicitContentDefaultStillRejectsPersistedPathsProfile() {
    String repository = "default-profile-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider()) {
      persistPathsProjection(provider, repository);

      SearchIndexProfileMismatchException mismatch =
          assertThrows(
              SearchIndexProfileMismatchException.class,
              () ->
                  new GitHistorySearchService(provider.getSessionFactory())
                      .searchCommitText(repository, "profile", 10));

      assertEquals(SearchIndexingProfile.CONTENT.id(), mismatch.configuredProfile());
      assertEquals(java.util.Set.of(SearchIndexingProfile.PATHS.id()), mismatch.persistedProfiles());
    }
  }

  private static void persistPathsProjection(
      HibernateSessionFactoryProvider provider, String repository) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setIndexProfile(SearchIndexingProfile.PATHS.id());
    projection.setRepositoryName(repository);
    projection.setObjectId("1111111111111111111111111111111111111111");
    projection.setShortMessage("profile compatibility");
    projection.setFullMessage("profile compatibility");
    projection.setAuthorName("Profile Test");
    projection.setAuthorEmail("profile@example.invalid");
    projection.setAuthorTime(Instant.parse("2026-01-01T10:00:00Z"));
    projection.setCommitterName("Profile Test");
    projection.setCommitterEmail("profile@example.invalid");
    projection.setCommitterTime(Instant.parse("2026-01-01T11:00:00Z"));
    projection.setChangedPaths("src/Main.java");
    projection.setChangedText(null);
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      session.persist(projection);
      transaction.commit();
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:default-profile-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }
}
