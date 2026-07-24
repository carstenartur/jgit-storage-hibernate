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

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class SearchRepositoryDeletionParticipantH2Test {

  @Test
  void deletesOnlySelectedRepositoryProjectionInCoreTransaction() {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties(), SearchEntities.annotatedClasses())) {
      persist(provider, projection("first", "1111111111111111111111111111111111111111"));
      persist(provider, projection("second", "2222222222222222222222222222222222222222"));

      DefaultHibernateRepositoryFactory factory =
          new DefaultHibernateRepositoryFactory(
              provider.getSessionFactory(), List.of(new SearchRepositoryDeletionParticipant()));
      RepositoryDeletionResult result =
          factory.deleteRepository(new RepositoryName("first"));

      assertEquals(1, result.projectionRows());
      assertEquals(List.of(), objectIds(provider, "first"));
      assertEquals(
          List.of("2222222222222222222222222222222222222222"),
          objectIds(provider, "second"));
    }
  }

  private static GitCommitIndex projection(String repositoryName, String objectId) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName(repositoryName);
    projection.setObjectId(objectId);
    projection.setShortMessage("Repository deletion projection");
    projection.setFullMessage("Repository deletion projection");
    projection.setAuthorName("Deletion Test");
    projection.setAuthorEmail("deletion@example.invalid");
    projection.setCommitTime(Instant.parse("2026-07-23T00:00:00Z"));
    projection.setChangedPaths("workflow.dsl");
    projection.setChangedText("workflow deletion");
    return projection;
  }

  private static void persist(HibernateSessionFactoryProvider provider, GitCommitIndex projection) {
    try (Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      session.persist(projection);
      session.getTransaction().commit();
    }
  }

  private static List<String> objectIds(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT c.objectId FROM GitCommitIndex c WHERE c.repositoryName = :repo "
                  + "ORDER BY c.objectId",
              String.class)
          .setParameter("repo", repositoryName)
          .getResultList();
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
}
