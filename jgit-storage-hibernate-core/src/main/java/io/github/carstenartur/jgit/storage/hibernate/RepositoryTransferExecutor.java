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
    requireInitialClone(request);
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
    requireInitialClone(request);
    requireEmptyTarget(target, request.target());

    TransferCounters counters = copyReachableObjects(source, target, resolvedRefs);
    if (request.verifyConnectivity()) {
      verifyConnectivity(target, resolvedRefs);
    }
    publishRefs(target, request, resolvedRefs);

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
        false);
  }

  private static TransferCounters copyReachableObjects(
      Repository source, Repository target, List<ResolvedRefTransfer> resolvedRefs)
      throws IOException {
    TransferCounters counters = new TransferCounters();
    try (ObjectReader sourceReader = source.newObjectReader();
        ObjectReader targetReader = target.newObjectReader();
        ObjectInserter targetInserter = target.newObjectInserter();
        ObjectWalk walk = new ObjectWalk(source)) {
      for (ResolvedRefTransfer resolved : resolvedRefs) {
        walk.markStart(walk.parseAny(resolved.sourceObjectId()));
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

  private static void publishRefs(
      Repository target,
      RepositoryTransferRequest request,
      List<ResolvedRefTransfer> resolvedRefs)
      throws IOException {
    BatchRefUpdate update = target.getRefDatabase().newBatchUpdate();
    update.setAtomic(true);
    update.setAllowNonFastForwards(false);
    update.setRefLogIdent(TRANSFER_IDENTITY);
    update.setRefLogMessage("transfer from " + request.source().value(), false);
    update.setForceRefLog(true);

    List<ReceiveCommand> commands = new ArrayList<>(resolvedRefs.size());
    for (ResolvedRefTransfer resolved : resolvedRefs) {
      ReceiveCommand command =
          new ReceiveCommand(
              ObjectId.zeroId(), resolved.sourceObjectId(), resolved.spec().targetRef());
      commands.add(command);
    }
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
  }

  private static void requireEmptyTarget(Repository target, RepositoryName targetName)
      throws IOException {
    List<Ref> existingRefs = target.getRefDatabase().getRefsByPrefix(Constants.R_REFS);
    if (!existingRefs.isEmpty()) {
      throw new HibernateStorageException(
          "Initial clone target " + targetName + " already contains refs");
    }
  }

  private static void requireInitialClone(RepositoryTransferRequest request) {
    if (request.mode() != RepositoryTransferMode.INITIAL_CLONE) {
      throw new UnsupportedOperationException(
          "Incremental logical repository transfer is not implemented yet");
    }
    if (request.targetRefPolicy() != TargetRefPolicy.CREATE_ONLY) {
      throw new UnsupportedOperationException(
          "Initial logical repository transfer currently requires CREATE_ONLY");
    }
    for (RefTransferSpec ref : request.refs()) {
      if (ref.expectedTargetObjectId() != null) {
        throw new IllegalArgumentException(
            "CREATE_ONLY transfers must not provide expected target object IDs");
      }
    }
  }

  record ResolvedRefTransfer(RefTransferSpec spec, ObjectId sourceObjectId) {}

  private static final class TransferCounters {
    private long objectsVisited;
    private long objectsTransferred;
    private long bytesTransferred;
  }
}
