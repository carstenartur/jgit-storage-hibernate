/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Default factory that opens Hibernate-backed JGit repositories from a {@link SessionFactory}. */
public final class DefaultHibernateRepositoryFactory implements HibernateRepositoryFactory {

  private static final ConcurrentMap<RepositoryScope, RepositoryLifecycle> REPOSITORY_LIFECYCLES =
      new ConcurrentHashMap<>();
  private static final Consumer<RepositoryAccessRequest> UNRESTRICTED_ACCESS = ignored -> {};

  private final SessionFactory sessionFactory;
  private final List<RepositoryDeletionParticipant> deletionParticipants;
  private final RepositoryTransferCheckpointHook transferCheckpointHook;

  public DefaultHibernateRepositoryFactory(SessionFactory sessionFactory) {
    this(sessionFactory, List.of(), RepositoryTransferCheckpointHook.NONE);
  }

  public DefaultHibernateRepositoryFactory(
      SessionFactory sessionFactory,
      Collection<? extends RepositoryDeletionParticipant> deletionParticipants) {
    this(sessionFactory, deletionParticipants, RepositoryTransferCheckpointHook.NONE);
  }

  DefaultHibernateRepositoryFactory(
      SessionFactory sessionFactory,
      Collection<? extends RepositoryDeletionParticipant> deletionParticipants,
      RepositoryTransferCheckpointHook transferCheckpointHook) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.deletionParticipants = List.copyOf(deletionParticipants);
    this.transferCheckpointHook =
        Objects.requireNonNull(transferCheckpointHook, "transferCheckpointHook");
  }

  @Override
  public HibernateGitStorage open(RepositoryName repositoryName) {
    return openStorage(repositoryName, true, UNRESTRICTED_ACCESS).storage();
  }

  HibernateGitStorage openExisting(
      RepositoryName repositoryName, Consumer<RepositoryAccessRequest> accessGuard) {
    return openStorage(repositoryName, false, accessGuard).storage();
  }

  @Override
  public RepositoryTransferResult transfer(RepositoryTransferRequest request) {
    Objects.requireNonNull(request, "request");
    boolean cleanupCreatedTarget = false;
    Throwable transferFailure = null;
    try (HibernateGitStorage sourceStorage =
        openStorage(request.source(), false, UNRESTRICTED_ACCESS).storage()) {
      List<RepositoryTransferExecutor.ResolvedRefTransfer> resolvedRefs =
          RepositoryTransferExecutor.resolveSourceRefs(sourceStorage.repository(), request);
      boolean createTarget = request.mode() == RepositoryTransferMode.INITIAL_CLONE;
      OpenedStorage openedTarget =
          openStorage(request.target(), createTarget, UNRESTRICTED_ACCESS);
      cleanupCreatedTarget = openedTarget.created();
      try (HibernateGitStorage targetStorage = openedTarget.storage()) {
        RepositoryTransferResult result =
            RepositoryTransferExecutor.transfer(
                sourceStorage.repository(),
                targetStorage.repository(),
                request,
                resolvedRefs,
                openedTarget.created(),
                transferCheckpointHook);
        cleanupCreatedTarget = false;
        return result;
      }
    } catch (IOException exception) {
      HibernateStorageException storageException =
          new HibernateStorageException(
              "Could not transfer Git history from "
                  + request.source()
                  + " to "
                  + request.target(),
              exception);
      transferFailure = storageException;
      throw storageException;
    } catch (RuntimeException exception) {
      transferFailure = exception;
      throw exception;
    } catch (Error error) {
      transferFailure = error;
      throw error;
    } finally {
      if (cleanupCreatedTarget) {
        cleanupFailedInitialTarget(request.target(), transferFailure);
      }
    }
  }

  @Override
  public RepositoryDeletionResult deleteRepository(RepositoryName repositoryName) {
    return deleteRepository(repositoryName, UNRESTRICTED_ACCESS);
  }

  RepositoryDeletionResult deleteRepository(
      RepositoryName repositoryName, Consumer<RepositoryAccessRequest> accessGuard) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Consumer<RepositoryAccessRequest> guard =
        Objects.requireNonNull(accessGuard, "accessGuard");
    RepositoryAccessRequest request =
        RepositoryAccessRequest.repository(
            repositoryName, RepositoryAccessOperation.DELETE_REPOSITORY);
    guard.accept(request);

    RepositoryScope scope = new RepositoryScope(sessionFactory, repositoryName.value());
    RepositoryLifecycle lifecycle = reserveDeletion(scope, repositoryName);
    try {
      return deleteRepositoryData(repositoryName, guard, request);
    } finally {
      releaseDeletion(scope, lifecycle);
    }
  }

  private void cleanupFailedInitialTarget(
      RepositoryName targetRepository, Throwable transferFailure) {
    try {
      deleteRepository(targetRepository);
    } catch (RuntimeException cleanupFailure) {
      if (transferFailure == null) {
        throw cleanupFailure;
      }
      transferFailure.addSuppressed(cleanupFailure);
    }
  }

  private OpenedStorage openStorage(
      RepositoryName repositoryName,
      boolean createIfMissing,
      Consumer<RepositoryAccessRequest> accessGuard) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Consumer<RepositoryAccessRequest> guard =
        Objects.requireNonNull(accessGuard, "accessGuard");
    guard.accept(
        RepositoryAccessRequest.repository(
            repositoryName, RepositoryAccessOperation.DISCOVER));
    guard.accept(
        RepositoryAccessRequest.repository(repositoryName, RepositoryAccessOperation.READ));

    RepositoryScope scope = new RepositoryScope(sessionFactory, repositoryName.value());
    reserveOpenHandle(scope, repositoryName);
    AtomicBoolean handleOpen = new AtomicBoolean(true);
    Runnable releaseHandle =
        () -> {
          if (handleOpen.compareAndSet(true, false)) {
            releaseOpenHandle(scope);
          }
        };
    HibernateRepository repository = null;
    boolean handedOff = false;
    try {
      if (!createIfMissing) {
        requireExistingRepositoryMetadata(repositoryName);
      }
      repository =
          createIfMissing
              ? HibernateRepository.create(
                  sessionFactory, repositoryName.value(), guard, releaseHandle)
              : HibernateRepository.openExisting(
                  sessionFactory, repositoryName.value(), guard, releaseHandle);
      boolean exists = repository.exists();
      if (!exists && !createIfMissing) {
        throw new HibernateStorageException(
            "Repository metadata exists but Git refs are not initialized for " + repositoryName);
      }
      if (!exists) {
        repository.create(true);
      }
      DefaultHibernateGitStorage storage = new DefaultHibernateGitStorage(repository);
      handedOff = true;
      return new OpenedStorage(storage, !exists);
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not open Hibernate-backed repository " + repositoryName, exception);
    } finally {
      if (!handedOff) {
        if (repository != null) {
          repository.close();
        }
        releaseHandle.run();
      }
    }
  }

  private void requireExistingRepositoryMetadata(RepositoryName repositoryName) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        boolean lifecycleExists =
            session.find(GitRepositoryLifecycleEntity.class, repositoryName.value()) != null;
        boolean lockExists =
            session.find(GitRepositoryLockEntity.class, repositoryName.value()) != null;
        if (!lifecycleExists && !lockExists) {
          throw new RepositoryDoesNotExistException(repositoryName);
        }
        if (lifecycleExists != lockExists) {
          throw new HibernateStorageException(
              "Incomplete repository metadata for "
                  + repositoryName
                  + ": lifecycle="
                  + lifecycleExists
                  + ", lock="
                  + lockExists);
        }
        transaction.commit();
      } catch (RuntimeException failure) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw failure;
      }
    } catch (HibernateStorageException handled) {
      throw handled;
    } catch (RuntimeException failure) {
      throw new HibernateStorageException(
          "Could not verify repository metadata for " + repositoryName, failure);
    }
  }

  private RepositoryDeletionResult deleteRepositoryData(
      RepositoryName repositoryName,
      Consumer<RepositoryAccessRequest> accessGuard,
      RepositoryAccessRequest deletionRequest) {
    try (HibernateRepository cacheScope =
            HibernateRepository.create(sessionFactory, repositoryName.value());
        Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        GitRepositoryLockEntity repositoryLock =
            session.find(
                GitRepositoryLockEntity.class,
                repositoryName.value(),
                LockModeType.PESSIMISTIC_WRITE);
        if (repositoryLock == null) {
          throw new HibernateStorageException(
              "Missing repository lock row for " + repositoryName.value());
        }
        GitRepositoryLifecycleEntity repositoryLifecycle =
            session.find(GitRepositoryLifecycleEntity.class, repositoryName.value());
        if (repositoryLifecycle == null) {
          throw new HibernateStorageException(
              "Missing repository lifecycle row for " + repositoryName.value());
        }

        accessGuard.accept(deletionRequest);

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

        // Keep exact result accounting above, then remove the durable parent. Its database cascade is
        // the race-safety net for any invisible pack row committed after the counted bulk delete.
        session.remove(repositoryLock);
        session.flush();
        session.remove(repositoryLifecycle);
        transaction.commit();

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

  private record OpenedStorage(HibernateGitStorage storage, boolean created) {}

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
