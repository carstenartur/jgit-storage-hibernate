/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.hibernate.Session;

/** Reference database using JGit DFS reftables persisted through the Hibernate object database. */
public class HibernateRefDatabase extends DfsReftableDatabase {

  private final HibernateRepository repository;

  /**
   * Create a reference database.
   *
   * @param repository owning Hibernate repository
   */
  public HibernateRefDatabase(HibernateRepository repository) {
    super(repository);
    this.repository = repository;
  }

  @Override
  public ReftableConfig getReftableConfig() {
    ReftableConfig config = new ReftableConfig();
    config.setAlignBlocks(false);
    config.setIndexObjects(false);
    config.fromConfig(getRepository().getConfig());
    return config;
  }

  @Override
  public boolean performsAtomicTransactions() {
    return true;
  }

  @Override
  public RefUpdate newUpdate(String refName, boolean detach) throws IOException {
    boolean detachingSymbolicRef = false;
    Ref ref = exactRef(refName);
    if (ref == null) {
      ref = new ObjectIdRef.Unpeeled(Ref.Storage.NEW, refName, null);
    } else {
      detachingSymbolicRef = detach && ref.isSymbolic();
    }

    HibernateRefUpdate update = new HibernateRefUpdate(this, ref);
    if (detachingSymbolicRef) {
      update.setDetachingSymbolicRef();
    }
    return update;
  }

  HibernateRepository repository() {
    return repository;
  }

  boolean compareAndPutRef(Ref oldRef, Ref newRef) throws IOException {
    return super.compareAndPut(oldRef, newRef);
  }

  boolean compareAndRemoveRef(Ref oldRef) throws IOException {
    return super.compareAndRemove(oldRef);
  }

  <T> T inTransaction(HibernateTransactionContext.Work<T> work) throws IOException {
    return repository.inTransaction(work);
  }

  void writeReflog(
      Session session,
      String refName,
      ObjectId oldId,
      ObjectId newId,
      PersonIdent who,
      String message) {
    repository.getReflogWriter().log(session, refName, oldId, newId, who, message);
  }
}
