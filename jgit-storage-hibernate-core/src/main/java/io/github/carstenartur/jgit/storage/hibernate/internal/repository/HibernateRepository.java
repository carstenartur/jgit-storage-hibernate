/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.repository;

import io.github.carstenartur.jgit.storage.hibernate.internal.objects.HibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.internal.refs.HibernateRefDatabase;
import io.github.carstenartur.jgit.storage.hibernate.internal.refs.HibernateReflogReader;
import io.github.carstenartur.jgit.storage.hibernate.internal.refs.HibernateReflogWriter;
import java.io.IOException;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.ReflogReader;
import org.hibernate.SessionFactory;

/** A JGit repository whose pack and reftable data is stored through Hibernate. */
public class HibernateRepository extends DfsRepository {

  private final HibernateObjDatabase objdb;
  private final HibernateRefDatabase refdb;
  private final HibernateReflogWriter reflogWriter;
  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private String gitwebDescription;

  public HibernateRepository(HibernateRepositoryBuilder builder) {
    super(builder);
    this.sessionFactory = builder.getSessionFactory();
    this.repositoryName = builder.getRepositoryName();
    this.objdb = new HibernateObjDatabase(this, new DfsReaderOptions(), sessionFactory, repositoryName);
    this.refdb = new HibernateRefDatabase(this);
    this.reflogWriter = new HibernateReflogWriter(sessionFactory, repositoryName);
  }

  @Override
  public HibernateObjDatabase getObjectDatabase() {
    return objdb;
  }

  @Override
  public RefDatabase getRefDatabase() {
    return refdb;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  public HibernateReflogWriter getReflogWriter() {
    return reflogWriter;
  }

  @Override
  public ReflogReader getReflogReader(String refName) throws IOException {
    return new HibernateReflogReader(sessionFactory, repositoryName, refName);
  }

  @Override
  @Nullable
  public String getGitwebDescription() {
    return gitwebDescription;
  }

  @Override
  public void setGitwebDescription(@Nullable String description) {
    this.gitwebDescription = description;
  }
}
