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
import java.io.IOException;
import java.time.Instant;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.hibernate.Session;

/** Ref update that commits the DFS reftable and queryable reflog entry in one transaction. */
final class HibernateRefUpdate extends RefUpdate {

  private final HibernateRefDatabase refDatabase;
  private Ref destinationRef;
  private RevWalk revWalk;

  HibernateRefUpdate(HibernateRefDatabase refDatabase, Ref ref) {
    super(ref);
    this.refDatabase = refDatabase;
  }

  @Override
  protected HibernateRefDatabase getRefDatabase() {
    return refDatabase;
  }

  @Override
  protected HibernateRepository getRepository() {
    return refDatabase.repository();
  }

  @Override
  protected boolean tryLock(boolean dereference) throws IOException {
    destinationRef = getRef();
    if (dereference) {
      destinationRef = destinationRef.getLeaf();
    }
    setOldObjectId(destinationRef.isSymbolic() ? null : destinationRef.getObjectId());
    return true;
  }

  @Override
  protected void unlock() {
    // The compare-and-put operation provides optimistic locking.
  }

  @Override
  public Result update(RevWalk walk) throws IOException {
    try {
      revWalk = walk;
      return super.update(walk);
    } finally {
      revWalk = null;
    }
  }

  @Override
  protected Result doUpdate(Result desiredResult) throws IOException {
    ObjectIdRef newRef;
    RevObject object = revWalk.parseAny(getNewObjectId());
    if (object instanceof RevTag) {
      newRef =
          new ObjectIdRef.PeeledTag(
              Ref.Storage.PACKED,
              destinationRef.getName(),
              getNewObjectId(),
              revWalk.peel(object).copy());
    } else {
      newRef =
          new ObjectIdRef.PeeledNonTag(
              Ref.Storage.PACKED, destinationRef.getName(), getNewObjectId());
    }

    try {
      return refDatabase.inTransaction(
          session -> {
            if (!refDatabase.compareAndPutRef(destinationRef, newRef)) {
              return Result.LOCK_FAILURE;
            }
            writeReflog(
                session,
                destinationRef.getName(),
                getOldObjectId(),
                getNewObjectId(),
                desiredResult);
            return desiredResult;
          });
    } catch (RuntimeException exception) {
      throw new IOException("Could not update ref " + destinationRef.getName(), exception);
    }
  }

  @Override
  protected Result doDelete(Result desiredResult) throws IOException {
    try {
      return refDatabase.inTransaction(
          session -> {
            if (!refDatabase.compareAndRemoveRef(destinationRef)) {
              return Result.LOCK_FAILURE;
            }
            writeReflog(
                session,
                destinationRef.getName(),
                getOldObjectId(),
                ObjectId.zeroId(),
                desiredResult);
            return desiredResult;
          });
    } catch (RuntimeException exception) {
      throw new IOException("Could not delete ref " + destinationRef.getName(), exception);
    }
  }

  @Override
  protected Result doLink(String target) throws IOException {
    SymbolicRef newRef =
        new SymbolicRef(
            destinationRef.getName(),
            new ObjectIdRef.Unpeeled(Ref.Storage.NEW, target, null));
    Result desiredResult =
        destinationRef.getStorage() == Ref.Storage.NEW ? Result.NEW : Result.FORCED;
    try {
      return refDatabase.inTransaction(
          session -> {
            if (!refDatabase.compareAndPutRef(destinationRef, newRef)) {
              return Result.LOCK_FAILURE;
            }
            writeReflog(
                session,
                destinationRef.getName(),
                getOldObjectId(),
                getNewObjectId(),
                desiredResult);
            return desiredResult;
          });
    } catch (RuntimeException exception) {
      throw new IOException("Could not link ref " + destinationRef.getName(), exception);
    }
  }

  private void writeReflog(
      Session session, String refName, ObjectId oldId, ObjectId newId, Result result) {
    String message = reflogMessage(result);
    if (message == null) {
      return;
    }
    refDatabase.writeReflog(session, refName, oldId, newId, reflogIdentity(), message);
  }

  private PersonIdent reflogIdentity() {
    PersonIdent configured = getRefLogIdent();
    return configured != null
        ? new PersonIdent(configured, Instant.now())
        : new PersonIdent(refDatabase.repository());
  }

  private String reflogMessage(Result result) {
    String message = getRefLogMessage();
    if (message == null || !isRefLogIncludingResult()) {
      return message;
    }
    String resultText = resultText(result);
    if (resultText == null) {
      return message;
    }
    return message.isEmpty() ? resultText : message + ": " + resultText;
  }

  private static String resultText(Result result) {
    return switch (result) {
      case FORCED -> ReflogEntry.PREFIX_FORCED_UPDATE;
      case FAST_FORWARD -> ReflogEntry.PREFIX_FAST_FORWARD;
      case NEW -> ReflogEntry.PREFIX_CREATED;
      default -> null;
    };
  }
}
