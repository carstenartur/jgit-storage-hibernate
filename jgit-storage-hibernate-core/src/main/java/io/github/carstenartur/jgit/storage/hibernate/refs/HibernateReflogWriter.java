/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Writes queryable reflog entries to the {@code git_reflog} table. */
public class HibernateReflogWriter {

  private final HibernateTransactionContext transactionContext;
  private final String repositoryName;

  /**
   * Create a standalone writer.
   *
   * <p>Calls made through this constructor use their own transaction. Normal repository ref updates
   * use the repository-scoped constructor so reftable and queryable reflog changes commit together.
   *
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   */
  public HibernateReflogWriter(SessionFactory sessionFactory, String repositoryName) {
    this(new HibernateTransactionContext(sessionFactory), repositoryName);
  }

  /**
   * Create a writer that joins repository storage transactions.
   *
   * @param transactionContext repository transaction context
   * @param repositoryName logical repository name
   */
  public HibernateReflogWriter(
      HibernateTransactionContext transactionContext, String repositoryName) {
    this.transactionContext = Objects.requireNonNull(transactionContext, "transactionContext");
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
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
    try {
      transactionContext.execute(
          session -> {
            log(session, refName, oldId, newId, who, message);
            return null;
          });
    } catch (IOException exception) {
      throw new HibernateStorageException("Could not write queryable reflog for " + refName, exception);
    }
  }

  void log(
      Session session,
      String refName,
      ObjectId oldId,
      ObjectId newId,
      PersonIdent who,
      String message) {
    session.persist(newEntry(refName, oldId, newId, who, message));
  }

  private GitReflogEntity newEntry(
      String refName, ObjectId oldId, ObjectId newId, PersonIdent who, String message) {
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
    return entry;
  }
}
