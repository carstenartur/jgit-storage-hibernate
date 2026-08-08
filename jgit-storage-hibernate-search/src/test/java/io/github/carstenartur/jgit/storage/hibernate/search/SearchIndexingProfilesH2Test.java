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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.search.service.SearchIndexProfileMismatchException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class SearchIndexingProfilesH2Test {

  private static final String PATH = "src/payments/Rules.java";

  @Test
  void defaultContentProfilePreservesPreviousPathAndBlobSemantics() throws Exception {
    try (Fixture fixture = fixture(null)) {
      ObjectId commit = fixture.commit("Update payment rules", PATH, "class Rules { String marker = \"alpha\"; }", null);
      GitCommitIndex projection = fixture.index(commit);

      assertEquals(SearchIndexingProfile.CONTENT.id(), projection.getIndexProfile());
      assertEquals(PATH, projection.getChangedPaths());
      assertTrue(projection.getChangedText().contains("alpha"));
      assertEquals(1, fixture.search().searchCommitText(fixture.name(), "alpha", 10).size());
      assertEquals(
          1,
          fixture
              .search()
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(fixture.name())
                      .touchingExactPath(PATH)
                      .limit(10)
                      .build())
              .size());
    }
  }

  @Test
  void metadataProfileAvoidsPathAndContentProjection() throws Exception {
    try (Fixture fixture = fixture(SearchIndexingProfile.METADATA)) {
      ObjectId commit = fixture.commit("Metadata marker only", PATH, "alpha content", null);
      GitCommitIndex projection = fixture.index(commit);

      assertEquals(SearchIndexingProfile.METADATA.id(), projection.getIndexProfile());
      assertEquals("", projection.getChangedPaths());
      assertNull(projection.getChangedText());
      assertEquals(1, fixture.search().searchCommitText(fixture.name(), "marker", 10).size());
      assertEquals(
          0,
          fixture
              .search()
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(fixture.name())
                      .touchingExactPath(PATH)
                      .limit(10)
                      .build())
              .size());
    }
  }

  @Test
  void pathsProfileIndexesPathsWithoutLoadingChangedContent() throws Exception {
    try (Fixture fixture = fixture(SearchIndexingProfile.PATHS)) {
      ObjectId commit = fixture.commit("Path-only update", PATH, "alpha content", null);
      GitCommitIndex projection = fixture.index(commit);

      assertEquals(SearchIndexingProfile.PATHS.id(), projection.getIndexProfile());
      assertEquals(PATH, projection.getChangedPaths());
      assertNull(projection.getChangedText());
      assertEquals(0, fixture.search().searchCommitText(fixture.name(), "alpha", 10).size());
      assertEquals(
          1,
          fixture
              .search()
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(fixture.name())
                      .touchingPathTerms("payments rules")
                      .limit(10)
                      .build())
              .size());
    }
  }

  @Test
  void diffHunksProfileIndexesOnlyAddedOrModifiedLines() throws Exception {
    try (Fixture fixture = fixture(SearchIndexingProfile.DIFF_HUNKS)) {
      ObjectId first = fixture.commit("Initial rules", PATH, "stable\nold-value\n", null);
      ObjectId second = fixture.commit("Change rules", PATH, "stable\nnew-value\n", first);
      GitCommitIndex projection = fixture.index(second);

      assertEquals(SearchIndexingProfile.DIFF_HUNKS.id(), projection.getIndexProfile());
      assertTrue(projection.getChangedText().contains("new-value"));
      assertFalse(projection.getChangedText().contains("old-value"));
      assertFalse(projection.getChangedText().contains("stable"));
      assertEquals(1, fixture.search().searchCommitText(fixture.name(), "new-value", 10).size());
      assertEquals(0, fixture.search().searchCommitText(fixture.name(), "stable", 10).size());
    }
  }

  @Test
  void mismatchedProfileFailsClosedUntilRepositoryIsRebuilt() throws Exception {
    try (Fixture fixture = fixture(SearchIndexingProfile.PATHS)) {
      ObjectId commit = fixture.commit("Profile migration", PATH, "alpha content", null);
      fixture.updateMain(commit);
      fixture.persistForeignProfile(commit, SearchIndexingProfile.CONTENT);

      SearchIndexProfileMismatchException mismatch =
          assertThrows(
              SearchIndexProfileMismatchException.class,
              () -> fixture.search().searchCommitText(fixture.name(), "migration", 10));
      assertEquals(SearchIndexingProfile.PATHS.id(), mismatch.configuredProfile());
      assertEquals(java.util.Set.of(SearchIndexingProfile.CONTENT.id()), mismatch.persistedProfiles());

      var rebuilt =
          new CommitProjectionRebuilder(fixture.provider().getSessionFactory())
              .rebuild(fixture.repository(), new RepositoryName(fixture.name()));
      assertEquals(1, rebuilt.indexedCommits());

      GitCommitIndex projection = fixture.load(commit);
      assertEquals(SearchIndexingProfile.PATHS.id(), projection.getIndexProfile());
      assertNull(projection.getChangedText());
      assertEquals(
          1,
          fixture
              .search()
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(fixture.name())
                      .touchingExactPath(PATH)
                      .limit(10)
                      .build())
              .size());
    }
  }

  private static Fixture fixture(SearchIndexingProfile profile) throws Exception {
    String name = "profile-h2-" + UUID.randomUUID();
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
    if (profile != null) {
      properties.put(SearchIndexingProfile.PROFILE_PROPERTY, profile.id());
    }
    HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
    HibernateRepository repository = HibernateRepository.create(provider.getSessionFactory(), name);
    repository.create(true);
    return new Fixture(name, provider, repository);
  }

  private record Fixture(
      String name, HibernateSessionFactoryProvider provider, HibernateRepository repository)
      implements AutoCloseable {

    ObjectId commit(String message, String path, String content, ObjectId parent) throws Exception {
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
        TreeFormatter tree = new TreeFormatter();
        tree.append(path, FileMode.REGULAR_FILE, blob);
        CommitBuilder builder = new CommitBuilder();
        builder.setTreeId(inserter.insert(tree));
        if (parent != null) {
          builder.setParentId(parent);
        }
        PersonIdent actor = new PersonIdent("Profile Test", "profile@example.invalid");
        builder.setAuthor(actor);
        builder.setCommitter(actor);
        builder.setMessage(message);
        ObjectId id = inserter.insert(builder);
        inserter.flush();
        return id;
      }
    }

    GitCommitIndex index(ObjectId commit) throws Exception {
      return new CommitIndexer(provider.getSessionFactory(), name).indexCommit(repository, commit);
    }

    GitHistorySearchService search() {
      return new GitHistorySearchService(provider.getSessionFactory());
    }

    void updateMain(ObjectId commit) throws Exception {
      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    void persistForeignProfile(ObjectId commit, SearchIndexingProfile profile) {
      GitCommitIndex projection = new GitCommitIndex();
      projection.setIndexProfile(profile.id());
      projection.setRepositoryName(name);
      projection.setObjectId(commit.name());
      projection.setShortMessage("Profile migration");
      projection.setFullMessage("Profile migration");
      projection.setAuthorName("Profile Test");
      projection.setAuthorEmail("profile@example.invalid");
      projection.setCommitterName("Profile Test");
      projection.setCommitterEmail("profile@example.invalid");
      projection.setChangedPaths(PATH);
      projection.setChangedText("alpha content");
      try (Session session = provider.getSessionFactory().openSession()) {
        Transaction transaction = session.beginTransaction();
        session.persist(projection);
        transaction.commit();
      }
    }

    GitCommitIndex load(ObjectId commit) {
      try (Session session = provider.getSessionFactory().openSession()) {
        return session
            .createQuery(
                "FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.objectId = :objectId",
                GitCommitIndex.class)
            .setParameter("repo", name)
            .setParameter("objectId", commit.name())
            .getSingleResult();
      }
    }

    @Override
    public void close() {
      repository.close();
      provider.close();
    }
  }
}
