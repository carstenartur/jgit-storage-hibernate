/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.SessionFactory;

/**
 * Read-only, repository-scoped storage inventory for trusted application/operator code.
 * Like {@link PackStorageMaintenance}, this service uses its supplied SessionFactory directly;
 * applications must enforce authorization before calling it.
 */
public final class RepositoryStorageStatistics {

  private final SessionFactory sessionFactory;

  public RepositoryStorageStatistics(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  /** Reads catalog metadata only. A missing repository has zero persisted pack groups and bytes. */
  public PackStorageStatistics packs(RepositoryName repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    return sessionFactory.fromSession(session -> {
      Object[] totals = session.createQuery(
              "SELECT COUNT(DISTINCT p.packName), COALESCE(SUM(p.fileSize), 0) "
                  + "FROM GitPackEntity p WHERE p.repositoryName = :repo", Object[].class)
          .setParameter("repo", repositoryName.value())
          .getSingleResult();
      return new PackStorageStatistics(((Number) totals[0]).longValue(), ((Number) totals[1]).longValue());
    });
  }

  /**
   * Counts distinct objects in every published pack of an existing repository, including garbage
   * packs. This scans pack indexes and object headers, uses memory proportional to unique object
   * IDs, and does not traverse refs or rewrite storage. Pause concurrent writes/repacking when a
   * stable inventory is required. Missing repositories throw instead of being created.
   */
  public StoredObjectStatistics objects(RepositoryName repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    var factory = new DefaultHibernateRepositoryFactory(sessionFactory);
    try (var storage = factory.openExisting(repositoryName, ignored -> {})) {
      var repository = (HibernateRepository) storage.repository();
      Set<ObjectId> seen = new HashSet<>();
      long[] counts = new long[Constants.OBJ_TAG + 1];
      try (var reader = repository.getObjectDatabase().newReader()) {
        reader.setAvoidUnreachableObjects(false);
        for (var pack : repository.getObjectDatabase().getPacks()) {
          var index = pack.getPackIndex(reader);
          for (long position = 0; position < index.getObjectCount(); position++) {
            ObjectId objectId = index.getObjectId(position);
            if (seen.add(objectId)) {
              int type = reader.open(objectId).getType();
              if (type < Constants.OBJ_COMMIT || type > Constants.OBJ_TAG) {
                throw new IOException("Unexpected stored Git object type: " + type);
              }
              counts[type]++;
            }
          }
        }
      }
      return new StoredObjectStatistics(counts[Constants.OBJ_COMMIT], counts[Constants.OBJ_TREE],
          counts[Constants.OBJ_BLOB], counts[Constants.OBJ_TAG]);
    } catch (IOException exception) {
      throw new HibernateStorageException("Could not inventory stored objects for " + repositoryName, exception);
    }
  }
}
