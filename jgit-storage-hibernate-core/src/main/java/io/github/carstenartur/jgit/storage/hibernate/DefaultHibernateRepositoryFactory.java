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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Default factory that opens Hibernate-backed JGit repositories from a {@link SessionFactory}. */
public final class DefaultHibernateRepositoryFactory implements HibernateRepositoryFactory {

  private static final ConcurrentMap<RepositoryScope, RepositoryLifecycle> REPOSITORY_LIFECYCLES =
      new ConcurrentHashMap<>();

  private final SessionFactory sessionFactory;
  private final List<RepositoryDeletionParticipant> deletionParticipants;

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
    RepositoryScope scope = new RepositoryScope(sessionFactory, repositoryName.value());
    reserveOpenHandle(scope, repositoryName);
    boolean handedOff = false;
    try {
      HibernateRepository repository =
          HibernateRepository.create(sessionFactory, repositoryName.value());
      if (!repository.exists()) {
        repository.create(true);
      }
      DefaultHibernateGitStorage storage =
          new DefaultHibernateGitStorage(repository, () -> releaseOpenHandle(scope));
      handedOff = true;
      return storage;
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not open Hibernate-backed repository " + repositoryName, exception);
    } finally {
      if (!handedOff) {
        releaseOpenHandle(scope);
      }
    }
  }

  @Override
  public RepositoryDeletionResult deleteRepository(RepositoryName repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    RepositoryScope scope = new RepositoryScope(sessionFactory, repositoryName.value());
    RepositoryLifecycle lifecycle = reserveDeletion(scope, repositoryName);
    try {
      return deleteRepositoryData(repositoryName);
    } finally {
      releaseDeletion(scope, lifecycle);
    }
  }

  private RepositoryDeletionResult deleteRepositoryData(RepositoryName repositoryName) {
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

        // Closing this repository clears its pack list and Reftable stack. Lifecycle reservation
        // prevents every factory sharing this SessionFactory from opening the same repository while
        // deletion is active, and deletion starts only after all such handles have closed.
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

  private static void reserveOpenHandle(
      RepositoryScope scope, RepositoryName repositoryName) {
    REPOSITORY_LIFECYCLES.compute(
        scope,
        (ignored, current) -> {
          RepositoryLifecycle lifecycle =
              current != null ? current : new RepositoryLifecycle();
          if (lifecycle.deleting) {
            throw new HibernateStorageException(
                "Repository " + repositoryName + " is currently being deleted");
          }
          lifecycle.openHandles++;
          return lifecycle;
        });
  }

  private static void releaseOpenHandle(RepositoryScope scope) {
    REPOSITORY_LIFECYCLES.computeIfPresent(
        scope,
        (ignored, lifecycle) -> {
          lifecycle.openHandles--;
          return lifecycle.openHandles == 0 && !lifecycle.deleting ? null : lifecycle;
        });
  }

  private static RepositoryLifecycle reserveDeletion(
      RepositoryScope scope, RepositoryName repositoryName) {
    AtomicReference<RepositoryLifecycle> reserved = new AtomicReference<>();
    REPOSITORY_LIFECYCLES.compute(
        scope,
        (ignored, current) -> {
          RepositoryLifecycle lifecycle =
              current != null ? current : new RepositoryLifecycle();
          if (lifecycle.deleting) {
            throw new HibernateStorageException(
                "Repository " + repositoryName + " is already being deleted");
          }
          if (lifecycle.openHandles > 0) {
            throw new HibernateStorageException(
                "Close all storage handles for repository "
                    + repositoryName
                    + " before deleting it; open handles: "
                    + lifecycle.openHandles);
          }
          lifecycle.deleting = true;
          reserved.set(lifecycle);
          return lifecycle;
        });
    return reserved.get();
  }

  private static void releaseDeletion(
      RepositoryScope scope, RepositoryLifecycle reservedLifecycle) {
    REPOSITORY_LIFECYCLES.computeIfPresent(
        scope,
        (ignored, current) -> {
          if (current != reservedLifecycle) {
            return current;
          }
          current.deleting = false;
          return current.openHandles == 0 ? null : current;
        });
  }

  private static final class RepositoryLifecycle {
    private int openHandles;
    private boolean deleting;
  }

  private static final class RepositoryScope {
    private final SessionFactory sessionFactory;
    private final String repositoryName;

    private RepositoryScope(SessionFactory sessionFactory, String repositoryName) {
      this.sessionFactory = sessionFactory;
      this.repositoryName = repositoryName;
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof RepositoryScope scope
              && sessionFactory == scope.sessionFactory
              && repositoryName.equals(scope.repositoryName);
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(sessionFactory) + repositoryName.hashCode();
    }
  }
}
