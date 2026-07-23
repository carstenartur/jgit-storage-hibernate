/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Default factory that opens Hibernate-backed JGit repositories from a {@link SessionFactory}. */
public final class DefaultHibernateRepositoryFactory implements HibernateRepositoryFactory {

  private final SessionFactory sessionFactory;
  private final List<RepositoryDeletionParticipant> deletionParticipants;
  private final ConcurrentMap<String, Set<HibernateRepository>> openRepositories =
      new ConcurrentHashMap<>();

  /**
   * Create a repository factory.
   *
   * @param sessionFactory Hibernate session factory configured with the core storage entities
   */
  public DefaultHibernateRepositoryFactory(SessionFactory sessionFactory) {
    this(sessionFactory, List.of());
  }

  /**
   * Create a repository factory with optional projection deletion participants.
   *
   * @param sessionFactory Hibernate session factory configured with all required entities
   * @param deletionParticipants optional module cleanup hooks
   */
  public DefaultHibernateRepositoryFactory(
      SessionFactory sessionFactory,
      Collection<? extends RepositoryDeletionParticipant> deletionParticipants) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.deletionParticipants = List.copyOf(deletionParticipants);
  }

  @Override
  public HibernateGitStorage open(RepositoryName repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    try {
      HibernateRepository repository =
          HibernateRepository.create(sessionFactory, repositoryName.value());
      if (!repository.exists()) {
        repository.create(true);
      }
      register(repositoryName, repository);
      return new DefaultHibernateGitStorage(
          repository, () -> unregister(repositoryName, repository));
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not open Hibernate-backed repository " + repositoryName, exception);
    }
  }

  @Override
  public RepositoryDeletionResult deleteRepository(RepositoryName repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Set<HibernateRepository> openHandles = openRepositories.get(repositoryName.value());
    if (openHandles != null && !openHandles.isEmpty()) {
      throw new HibernateStorageException(
          "Close all storage handles for repository "
              + repositoryName
              + " before deleting it; open handles: "
              + openHandles.size());
    }

    try (HibernateRepository cacheScope =
            HibernateRepository.create(sessionFactory, repositoryName.value());
        Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        int projectionRows = 0;
        for (RepositoryDeletionParticipant participant : deletionParticipants) {
          projectionRows =
              Math.addExact(
                  projectionRows, participant.deleteRepository(session, repositoryName));
        }
        int reflogRows =
            session
                .createMutationQuery(
                    "DELETE FROM GitReflogEntity r WHERE r.repositoryName = :repo")
                .setParameter("repo", repositoryName.value())
                .executeUpdate();
        int packRows =
            session
                .createMutationQuery(
                    "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo")
                .setParameter("repo", repositoryName.value())
                .executeUpdate();
        transaction.commit();

        // Closing this repository clears its pack list and reftable stack. Other handles are
        // prohibited above, so no repository-scoped DFS cache can retain deleted state.
        cacheScope.getRefDatabase().refresh();
        return new RepositoryDeletionResult(packRows, reflogRows, projectionRows);
      } catch (RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw exception;
      }
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof HibernateStorageException storageException) {
        throw storageException;
      }
      throw new HibernateStorageException(
          "Could not delete Hibernate-backed repository " + repositoryName, exception);
    }
  }

  private void register(RepositoryName repositoryName, HibernateRepository repository) {
    openRepositories
        .computeIfAbsent(repositoryName.value(), ignored -> ConcurrentHashMap.newKeySet())
        .add(repository);
  }

  private void unregister(RepositoryName repositoryName, HibernateRepository repository) {
    openRepositories.computeIfPresent(
        repositoryName.value(),
        (ignored, repositories) -> {
          repositories.remove(repository);
          return repositories.isEmpty() ? null : repositories;
        });
  }
}
