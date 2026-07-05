/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.refs;

import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitReflogEntity;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.lib.CheckoutEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Reads persistent reflog entries from the database. */
public class HibernateReflogReader implements ReflogReader {
  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final String refName;

  public HibernateReflogReader(SessionFactory sessionFactory, String repositoryName, String refName) {
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
    this.refName = refName;
  }

  @Override
  public ReflogEntry getLastEntry() throws IOException {
    List<ReflogEntry> entries = getReverseEntries(1);
    return entries.isEmpty() ? null : entries.get(0);
  }

  @Override
  public List<ReflogEntry> getReverseEntries() throws IOException {
    return getReverseEntries(Integer.MAX_VALUE);
  }

  @Override
  public ReflogEntry getReverseEntry(int number) throws IOException {
    List<ReflogEntry> entries = getReverseEntries(number + 1);
    return number < entries.size() ? entries.get(number) : null;
  }

  @Override
  public List<ReflogEntry> getReverseEntries(int max) throws IOException {
    try (Session session = sessionFactory.openSession()) {
      List<GitReflogEntity> entities =
          session
              .createQuery(
                  "FROM GitReflogEntity r WHERE r.repositoryName = :repo AND r.refName = :ref ORDER BY r.id DESC",
                  GitReflogEntity.class)
              .setParameter("repo", repositoryName)
              .setParameter("ref", refName)
              .setMaxResults(max)
              .getResultList();
      List<ReflogEntry> result = new ArrayList<>(entities.size());
      for (GitReflogEntity entity : entities) {
        result.add(new DbReflogEntry(entity));
      }
      return Collections.unmodifiableList(result);
    }
  }

  static class DbReflogEntry implements ReflogEntry {
    private final ObjectId oldId;
    private final ObjectId newId;
    private final PersonIdent author;
    private final String comment;

    DbReflogEntry(GitReflogEntity entity) {
      this.oldId = entity.getOldId() != null ? ObjectId.fromString(entity.getOldId()) : ObjectId.zeroId();
      this.newId = entity.getNewId() != null ? ObjectId.fromString(entity.getNewId()) : ObjectId.zeroId();
      this.author =
          new PersonIdent(
              entity.getWhoName() != null ? entity.getWhoName() : "",
              entity.getWhoEmail() != null ? entity.getWhoEmail() : "",
              entity.getWhen(),
              ZoneOffset.UTC);
      this.comment = entity.getMessage() != null ? entity.getMessage() : "";
    }

    @Override
    public ObjectId getOldId() { return oldId; }

    @Override
    public ObjectId getNewId() { return newId; }

    @Override
    public PersonIdent getWho() { return author; }

    @Override
    public String getComment() { return comment; }

    @Override
    public CheckoutEntry parseCheckout() { return null; }
  }
}
