/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext.TransactionScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.internal.submodule.SubmoduleValidator.SubmoduleValidationException;
import org.eclipse.jgit.transport.ReceivePack;

/**
 * {@link ReceivePack} adapter that groups one logical push into one Hibernate transaction.
 *
 * <p>The transaction starts immediately before JGit begins pack ingestion. Pack files are still
 * streamed into the storage implementation's temporary file, but pack publication, indexes,
 * reftables, reflogs and ref updates join one Hibernate session and transaction. A ref-only push
 * starts the transaction immediately before command execution.
 *
 * <p>The transaction commits after command execution and before JGit sends its success status or
 * invokes post-receive hooks. A failure before commit rolls back the transaction and invalidates the
 * repository's DFS and reftable caches.
 *
 * <p>Instances are created by {@link HibernateRepository#newReceivePack()} and, like ordinary JGit
 * receive-pack instances, are intended for one connection.
 */
public final class HibernateReceivePack extends ReceivePack {

  private final HibernateRepository repository;
  private TransactionScope transactionScope;

  HibernateReceivePack(HibernateRepository repository) {
    super(repository);
    this.repository = repository;
  }

  @Override
  protected void receivePackAndCheckConnectivity()
      throws IOException, LargeObjectException, SubmoduleValidationException {
    beginTransactionIfNecessary();
    try {
      super.receivePackAndCheckConnectivity();
    } catch (IOException | RuntimeException | SubmoduleValidationException | Error failure) {
      rollbackPending(failure);
      throw failure;
    }
  }

  @Override
  protected void executeCommands() {
    beginTransactionIfNecessary();
    try {
      super.executeCommands();
      transactionScope.commit();
      transactionScope = null;
    } catch (RuntimeException | Error failure) {
      rollbackPending(failure);
      throw failure;
    }
  }

  @Override
  public void receive(InputStream input, OutputStream output, OutputStream messages)
      throws IOException {
    try {
      super.receive(input, output, messages);
    } catch (IOException | RuntimeException | Error failure) {
      rollbackPending(failure);
      throw failure;
    } finally {
      rollbackPending(null);
    }
  }

  @Override
  public void receiveWithExceptionPropagation(
      InputStream input, OutputStream output, OutputStream messages) throws IOException {
    try {
      super.receiveWithExceptionPropagation(input, output, messages);
    } catch (IOException | RuntimeException | Error failure) {
      rollbackPending(failure);
      throw failure;
    } finally {
      rollbackPending(null);
    }
  }

  private void beginTransactionIfNecessary() {
    if (transactionScope == null) {
      transactionScope = repository.beginReceiveTransaction();
    }
  }

  private void rollbackPending(Throwable originalFailure) {
    TransactionScope pending = transactionScope;
    if (pending == null) {
      return;
    }
    transactionScope = null;

    RuntimeException cleanupFailure = null;
    try {
      pending.rollback();
    } catch (RuntimeException failure) {
      cleanupFailure = failure;
    }
    try {
      repository.resetStorageCaches();
    } catch (RuntimeException failure) {
      if (cleanupFailure == null) {
        cleanupFailure = failure;
      } else {
        cleanupFailure.addSuppressed(failure);
      }
    }

    if (cleanupFailure != null) {
      if (originalFailure != null) {
        originalFailure.addSuppressed(cleanupFailure);
      } else {
        throw cleanupFailure;
      }
    }
  }
}
