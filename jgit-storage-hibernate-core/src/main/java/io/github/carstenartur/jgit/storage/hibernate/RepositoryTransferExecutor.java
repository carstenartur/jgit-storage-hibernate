/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

final class RepositoryTransferExecutor {

  private static final PersonIdent TRANSFER_IDENTITY =
      new PersonIdent("jgit-storage-hibernate", "noreply@localhost");

  private RepositoryTransferExecutor() {}

  static List<ResolvedRefTransfer> resolveSourceRefs(
      Repository source, RepositoryTransferRequest request) throws IOException {
    List<ResolvedRefTransfer> resolved = new ArrayList<>(request.refs().size());
    for (RefTransferSpec spec : request.refs()) {
      Ref sourceRef = source.exactRef(spec.sourceRef());
      if (sourceRef == null || sourceRef.getObjectId() == null) {
        throw new HibernateStorageException(
            "Source ref " + spec.sourceRef() + " does not exist in " + request.source());
      }
      if (sourceRef.isSymbolic()) {
        throw new HibernateStorageException(
            "Symbolic source refs are not supported: " + spec.sourceRef());
      }
      resolved.add(new ResolvedRefTransfer(spec, sourceRef.getObjectId().copy()));
    }
    return List.copyOf(resolved);
  }

  static RepositoryTransferResult transfer(
      Repository source,
      Repository target,
      RepositoryTransferRequest request,
      List<ResolvedRefTransfer> resolvedRefs,
      boolean targetCreated)
      throws IOException {
    return transfer(
        source,
        target,
        request,
        resolvedRefs,
        targetCreated,
        TransferExecutionObserver.NO_OP);
  }

  static RepositoryTransferResult transfer(
      Repository source,
      Repository target,
      RepositoryTransferRequest request,
      List<ResolvedRefTransfer> resolvedRefs,
      boolean targetCreated,
      TransferExecutionObserver observer)
      throws IOException {
    observer.stage(RepositoryTransferStage.TARGET_PRECONDITION);
    List<Ref> targetRefs = target.getRefDatabase().getRefsByPrefix(Constants.R_REFS);
    if (request.mode() == RepositoryTransferMode.INITIAL_CLONE && !targetRefs.isEmpty()) {
      throw new HibernateStorageException(
          "Initial clone target " + request.target() + " already contains refs");
    }

    List<PreparedRefTransfer> preparedRefs = prepareTargetRefs(target, request, resolvedRefs);
    observer.stage(RepositoryTransferStage.OBJECT_TRANSFER);
    TransferCounters counters = copyReachableObjects(source, target, resolvedRefs, targetRefs);
    if (request.verifyConnectivity()) {
      observer.stage(RepositoryTransferStage.CONNECTIVITY_VERIFICATION);
      verifyConnectivity(target, resolvedRefs);
    }
    observer.stage(RepositoryTransferStage.REF_POLICY_VALIDATION);
    validateFastForwards(target, request.targetRefPolicy(), preparedRefs);

    observer.stage(RepositoryTransferStage.REF_PUBLICATION);
    observer.beforeRefPublication(
        publication(request, preparedRefs, counters, targetCreated));
    boolean refsChanged = publishRefs(target, request, preparedRefs);

    Map<String, RefTransferResult> refResults = new LinkedHashMap<>();
    for (ResolvedRefTransfer resolved : resolvedRefs) {
      Ref targetRef = target.exactRef(resolved.spec().targetRef());
      if (targetRef == null || targetRef.getObjectId() == null) {
        throw new HibernateStorageException(
            "Target ref was not visible after publication: " + resolved.spec().targetRef());
      }
      RefTransferResult result =
          new RefTransferResult(
              resolved.spec().sourceRef(),
              resolved.spec().targetRef(),
              resolved.sourceObjectId(),
              targetRef.getObjectId());
      refResults.put(resolved.spec().targetRef(), result);
    }

    return new RepositoryTransferResult(
        request.source(),
        request.target(),
        counters.objectsVisited,
        counters.objectsTransferred,
        counters.bytesTransferred,
        refResults,
        targetCreated,
        counters.objectsTransferred == 0 && !refsChanged);
  }

  private static RepositoryTransferPublication publication(
      RepositoryTransferRequest request,
      List<PreparedRefTransfer> preparedRefs,
      TransferCounters counters,
      boolean targetCreated) {
    List<RepositoryTransferRefUpdate> refs = new ArrayList<>(preparedRefs.size());
    for (PreparedRefTransfer prepared : preparedRefs) {
      ObjectId requiredTarget =
          prepared.changed()
              ? prepared.commandOldObjectId()
              : prepared.currentTargetObjectId();
      refs.add(
          new RepositoryTransferRefUpdate(
              prepared.resolved().spec().sourceRef(),
              prepared.resolved().spec().targetRef(),
              prepared.resolved().sourceObjectId(),
              prepared.currentTargetObjectId(),
              requiredTarget,
              prepared.changed()));
    }
    return new RepositoryTransferPublication(
        request,
        refs,
        counters.objectsVisited,
        counters.objectsTransferred,
        counters.bytesTransferred,
        targetCreated);
  }

  private static List<PreparedRefTransfer> prepareTargetRefs(
      Repository target,
      RepositoryTransferRequest request,
      List<ResolvedRefTransfer> resolvedRefs)
      throws IOException {
    List<PreparedRefTransfer> prepared = new ArrayList<>(resolvedRefs.size());
    for (ResolvedRefTransfer resolved : resolvedRefs) {
      Ref targetRef = target.exactRef(resolved.spec().targetRef());
      if (targetRef != null && targetRef.isSymbolic()) {
        throw new HibernateStorageException(
            "Symbolic target refs are not supported: " + resolved.spec().targetRef());
      }
      ObjectId current =
          targetRef == null || targetRef.getObjectId() == null
              ? ObjectId.zeroId()
              : targetRef.getObjectId().copy();
      boolean unchanged = current.equals(resolved.sourceObjectId());
      ObjectId commandOldId;

      switch (request.targetRefPolicy()) {
        case CREATE_ONLY -> {
          if (!current.equals(ObjectId.zeroId()) && !unchanged) {
            throw new HibernateStorageException(
                "CREATE_ONLY target ref already exists: " + resolved.spec().targetRef());
          }
          commandOldId = ObjectId.zeroId();
        }
        case FAST_FORWARD_ONLY -> {
          if (current.equals(ObjectId.zeroId()) && !unchanged) {
            throw new HibernateStorageException(
                "FAST_FORWARD_ONLY target ref does not exist: " + resolved.spec().targetRef());
          }
          commandOldId = current;
        }
        case COMPARE_AND_SET -> {
          ObjectId expected = resolved.spec().expectedTargetObjectId();
          if (!current.equals(expected) && !unchanged) {
            throw new HibernateStorageException(
                "COMPARE_AND_SET target ref changed: "
                    + resolved.spec().targetRef()
                    + " expected "
                    + expected.name()
                    + " but was "
                    + current.name());
          }
          commandOldId = expected;
        }
        case FORCE -> {
          ObjectId expected = resolved.spec().expectedTargetObjectId();
          if (expected != null && !current.equals(expected) && !unchanged) {
            throw new HibernateStorageException(
                "FORCE target ref changed: "
                    + resolved.spec().targetRef()
                    + " expected "
                    + expected.name()
                    + " but was "
                    + current.name());
          }
          commandOldId = current;
        }
        default -> throw new IllegalStateException("Unhandled target ref policy");
      }
      prepared.add(new PreparedRefTransfer(resolved, current, commandOldId, !unchanged));
    }
    return List.copyOf(prepared);
  }

  private static TransferCounters copyReachableObjects(
      Repository source,
      Repository target,
      List<ResolvedRefTransfer> resolvedRefs,
      List<Ref> targetRefs)
      throws IOException {
    TransferCounters counters = new TransferCounters();
    try (ObjectReader sourceReader = source.newObjectReader();
        ObjectReader targetReader = target.newObjectReader();
        ObjectInserter targetInserter = target.newObjectInserter();
        ObjectWalk walk = new ObjectWalk(source)) {
      for (ResolvedRefTransfer resolved : resolvedRefs) {
        walk.markStart(walk.parseAny(resolved.sourceObjectId()));
      }
      for (Ref targetRef : targetRefs) {
        ObjectId targetId = targetRef.getObjectId();
        if (targetId != null && sourceReader.has(targetId)) {
          walk.markUninteresting(walk.parseAny(targetId));
        }
      }

      RevCommit commit;
      while ((commit = walk.next()) != null) {
        copyObject(commit, sourceReader, targetReader, targetInserter, counters);
      }
      RevObject object;
      while ((object = walk.nextObject()) != null) {
        copyObject(object, sourceReader, targetReader, targetInserter, counters);
      }
      targetInserter.flush();
    }
    return counters;
  }

  private static void copyObject(
      RevObject object,
      ObjectReader sourceReader,
      ObjectReader targetReader,
      ObjectInserter targetInserter,
      TransferCounters counters)
      throws IOException {
    counters.objectsVisited = Math.addExact(counters.objectsVisited, 1);
    if (targetReader.has(object)) {
      return;
    }

    ObjectLoader loader = sourceReader.open(object);
    ObjectId inserted;
    try (ObjectStream stream = loader.openStream()) {
      inserted = targetInserter.insert(loader.getType(), loader.getSize(), stream);
    }
    if (!inserted.equals(object)) {
      throw new HibernateStorageException(
          "Canonical object ID changed while transferring " + object.name());
    }
    counters.objectsTransferred = Math.addExact(counters.objectsTransferred, 1);
    counters.bytesTransferred = Math.addExact(counters.bytesTransferred, loader.getSize());
  }

  private static void verifyConnectivity(
      Repository target, List<ResolvedRefTransfer> resolvedRefs) throws IOException {
    try (ObjectWalk walk = new ObjectWalk(target)) {
      for (ResolvedRefTransfer resolved : resolvedRefs) {
        walk.markStart(walk.parseAny(resolved.sourceObjectId()));
      }
      while (walk.next() != null) {
        // Traversal itself proves that every commit and parent can be opened.
      }
      while (walk.nextObject() != null) {
        // Traversal itself proves that every tree, blob and tag can be opened.
      }
    }
  }

  private static void validateFastForwards(
      Repository target,
      TargetRefPolicy policy,
      List<PreparedRefTransfer> preparedRefs)
      throws IOException {
    if (policy != TargetRefPolicy.FAST_FORWARD_ONLY
        && policy != TargetRefPolicy.COMPARE_AND_SET) {
      return;
    }
    try (RevWalk walk = new RevWalk(target)) {
      for (PreparedRefTransfer prepared : preparedRefs) {
        if (!prepared.changed()) {
          continue;
        }
        RevCommit newCommit =
            requireCommit(
                walk,
                prepared.resolved().sourceObjectId(),
                policy,
                prepared.resolved().spec().targetRef());
        if (prepared.currentTargetObjectId().equals(ObjectId.zeroId())) {
          continue;
        }
        RevCommit oldCommit =
            requireCommit(
                walk,
                prepared.currentTargetObjectId(),
                policy,
                prepared.resolved().spec().targetRef());
        if (!walk.isMergedInto(oldCommit, newCommit)) {
          throw new HibernateStorageException(
              "Non-fast-forward transfer rejected for "
                  + prepared.resolved().spec().targetRef());
        }
      }
    }
  }

  private static RevCommit requireCommit(
      RevWalk walk, ObjectId objectId, TargetRefPolicy policy, String targetRef) throws IOException {
    RevObject object = walk.parseAny(objectId);
    if (object instanceof RevCommit commit) {
      return commit;
    }
    throw new HibernateStorageException(
        policy + " requires commit-valued refs: " + targetRef + " points to " + object.name());
  }

  private static boolean publishRefs(
      Repository target,
      RepositoryTransferRequest request,
      List<PreparedRefTransfer> preparedRefs)
      throws IOException {
    List<ReceiveCommand> commands = new ArrayList<>(preparedRefs.size());
    for (PreparedRefTransfer prepared : preparedRefs) {
      if (!prepared.changed()) {
        continue;
      }
      commands.add(
          new ReceiveCommand(
              prepared.commandOldObjectId(),
              prepared.resolved().sourceObjectId(),
              prepared.resolved().spec().targetRef()));
    }
    if (commands.isEmpty()) {
      return false;
    }

    BatchRefUpdate update = target.getRefDatabase().newBatchUpdate();
    update.setAtomic(true);
    update.setAllowNonFastForwards(request.targetRefPolicy() == TargetRefPolicy.FORCE);
    update.setRefLogIdent(TRANSFER_IDENTITY);
    update.setRefLogMessage("transfer from " + request.source().value(), false);
    update.setForceRefLog(true);
    update.addCommand(commands);
    try (RevWalk walk = new RevWalk(target)) {
      update.execute(walk, NullProgressMonitor.INSTANCE);
    }

    for (ReceiveCommand command : commands) {
      if (command.getResult() != ReceiveCommand.Result.OK) {
        throw new HibernateStorageException(
            "Could not publish target ref "
                + command.getRefName()
                + ": "
                + command.getResult()
                + (command.getMessage() == null ? "" : " (" + command.getMessage() + ")"));
      }
    }
    return true;
  }

  record ResolvedRefTransfer(RefTransferSpec spec, ObjectId sourceObjectId) {}

  interface TransferExecutionObserver {
    TransferExecutionObserver NO_OP =
        new TransferExecutionObserver() {
          @Override
          public void stage(RepositoryTransferStage stage) {}

          @Override
          public void beforeRefPublication(RepositoryTransferPublication publication) {}
        };

    void stage(RepositoryTransferStage stage);

    void beforeRefPublication(RepositoryTransferPublication publication);
  }

  private record PreparedRefTransfer(
      ResolvedRefTransfer resolved,
      ObjectId currentTargetObjectId,
      ObjectId commandOldObjectId,
      boolean changed) {}

  private static final class TransferCounters {
    private long objectsVisited;
    private long objectsTransferred;
    private long bytesTransferred;
  }
}
