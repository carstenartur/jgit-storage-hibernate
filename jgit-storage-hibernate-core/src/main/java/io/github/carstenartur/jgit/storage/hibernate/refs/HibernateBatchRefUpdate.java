/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableBatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/** Reftable batch update with optional lock-bound repository authorization. */
final class HibernateBatchRefUpdate extends DfsReftableBatchRefUpdate {

  private static final String ACCESS_DENIED_MESSAGE = "repository access denied";
  private static final String TRANSACTION_FAILURE_MESSAGE = "repository transaction failed";
  private static final int MAXIMUM_FAILURE_DETAIL_LENGTH = 256;

  private final HibernateRefDatabase refDatabase;
  private final boolean authorize;
  private final boolean wrapInRepositoryTransaction;
  private RevWalk activeWalk;

  HibernateBatchRefUpdate(
      HibernateRefDatabase refDatabase,
      boolean authorize,
      boolean wrapInRepositoryTransaction) {
    super(refDatabase, refDatabase.repository().getObjectDatabase());
    this.refDatabase = refDatabase;
    this.authorize = authorize;
    this.wrapInRepositoryTransaction = wrapInRepositoryTransaction;
  }

  @Override
  public void execute(RevWalk walk, ProgressMonitor monitor, List<String> options) {
    if (!wrapInRepositoryTransaction) {
      executeWithinTransaction(walk, monitor, options);
      return;
    }
    try {
      refDatabase.inTransaction(
          session -> {
            executeWithinTransaction(walk, monitor, options);
            return null;
          });
    } catch (IOException | RuntimeException exception) {
      rejectAll(getCommands(), transactionFailureMessage(exception));
    }
  }

  private void executeWithinTransaction(
      RevWalk walk, ProgressMonitor monitor, List<String> options) {
    activeWalk = walk;
    try {
      super.execute(
          walk,
          monitor != null ? monitor : NullProgressMonitor.INSTANCE,
          options);
    } finally {
      activeWalk = null;
    }
  }

  @Override
  protected void applyUpdates(List<Ref> newRefs, List<ReceiveCommand> pending)
      throws IOException {
    if (authorize && !authorize(pending)) {
      return;
    }
    super.applyUpdates(newRefs, pending);
  }

  private boolean authorize(List<ReceiveCommand> pending) throws IOException {
    for (ReceiveCommand command : pending) {
      if (command.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) {
        continue;
      }
      if (command.getType() == ReceiveCommand.Type.UPDATE
          && command.getOldSymref() == null
          && command.getNewSymref() == null) {
        command.updateType(activeWalk);
      }
      try {
        refDatabase.repository().requireAccess(accessRequest(command));
      } catch (RepositoryAccessDeniedException denied) {
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, ACCESS_DENIED_MESSAGE);
        ReceiveCommand.abort(pending);
        return false;
      }
    }
    return true;
  }

  private RepositoryAccessRequest accessRequest(ReceiveCommand command) {
    RepositoryAccessOperation operation =
        switch (command.getType()) {
          case CREATE -> RepositoryAccessOperation.CREATE_REF;
          case UPDATE -> RepositoryAccessOperation.UPDATE_REF;
          case UPDATE_NONFASTFORWARD -> RepositoryAccessOperation.FORCE_UPDATE;
          case DELETE -> RepositoryAccessOperation.DELETE_REF;
        };
    return RepositoryAccessRequest.ref(
        refDatabase.repository().getLogicalRepositoryName(),
        operation,
        command.getRefName(),
        command.getOldId(),
        command.getNewId());
  }

  static void rejectAll(List<ReceiveCommand> commands, String message) {
    for (ReceiveCommand command : commands) {
      command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, message);
    }
  }

  static String transactionFailureMessage(Exception exception) {
    String detail = exception.getMessage();
    String type = exception.getClass().getSimpleName();
    String prefix =
        type.isBlank()
            ? TRANSACTION_FAILURE_MESSAGE
            : TRANSACTION_FAILURE_MESSAGE + " (" + type + ")";
    if (detail == null || detail.isBlank()) {
      return prefix;
    }
    String normalized = detail.replaceAll("\\s+", " ").trim();
    if (normalized.length() > MAXIMUM_FAILURE_DETAIL_LENGTH) {
      normalized = normalized.substring(0, MAXIMUM_FAILURE_DETAIL_LENGTH) + "…";
    }
    return prefix + ": " + normalized;
  }
}
