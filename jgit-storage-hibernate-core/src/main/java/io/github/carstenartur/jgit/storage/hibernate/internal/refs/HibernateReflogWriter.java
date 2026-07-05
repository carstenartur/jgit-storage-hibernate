/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.refs;

import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitReflogEntity;
import java.time.Instant;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Writes persistent reflog entries to the database. */
public class HibernateReflogWriter {
  private final SessionFactory sessionFactory;
  private final String repositoryName;

  public HibernateReflogWriter(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
  }

  public void log(String refName, ObjectId oldId, ObjectId newId, PersonIdent author, String message) {
    GitReflogEntity entry = new GitReflogEntity();
    entry.setRepositoryName(repositoryName);
    entry.setRefName(refName);
    entry.setOldId(oldId == null ? null : oldId.name());
    entry.setNewId(newId == null ? null : newId.name());
    if (author != null) {
      entry.setWhoName(author.getName());
      entry.setWhoEmail(author.getEmailAddress());
      entry.setWhen(author.getWhenAsInstant());
    } else {
      entry.setWhen(Instant.now());
    }
    entry.setMessage(message);
    try (Session session = sessionFactory.openSession()) {
      session.beginTransaction();
      session.persist(entry);
      session.getTransaction().commit();
    }
  }
}
