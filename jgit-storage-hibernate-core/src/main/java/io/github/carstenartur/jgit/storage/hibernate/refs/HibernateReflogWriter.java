/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import java.time.Instant;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Writes queryable reflog entries to the {@code git_reflog} table. */
public class HibernateReflogWriter {

  private final SessionFactory sessionFactory;
  private final String repositoryName;

  /**
   * Create a writer.
   *
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   */
  public HibernateReflogWriter(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
  }

  /**
   * Append a reflog entry.
   *
   * @param refName reference name
   * @param oldId old object id, or {@code null}
   * @param newId new object id, or {@code null}
   * @param who actor
   * @param message reflog message
   */
  public void log(String refName, ObjectId oldId, ObjectId newId, PersonIdent who, String message) {
    GitReflogEntity entry = new GitReflogEntity();
    entry.setRepositoryName(repositoryName);
    entry.setRefName(refName);
    entry.setOldId(oldId != null ? oldId.name() : ObjectId.zeroId().name());
    entry.setNewId(newId != null ? newId.name() : ObjectId.zeroId().name());
    if (who != null) {
      entry.setWhoName(who.getName());
      entry.setWhoEmail(who.getEmailAddress());
      entry.setWhen(who.getWhenAsInstant());
    } else {
      entry.setWhen(Instant.now());
    }
    entry.setMessage(message);

    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        session.persist(entry);
        transaction.commit();
      } catch (RuntimeException e) {
        transaction.rollback();
        throw e;
      }
    }
  }
}
