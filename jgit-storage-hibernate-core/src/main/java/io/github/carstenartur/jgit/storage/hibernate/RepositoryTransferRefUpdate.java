/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Exact ref evidence supplied immediately before atomic target publication.
 *
 * @param sourceRef captured source ref
 * @param targetRef requested target ref
 * @param sourceObjectId immutable source snapshot and desired target ID
 * @param capturedTargetObjectId target value observed before object transfer, or zero ID
 * @param requiredTargetObjectId exact old ID required by the final atomic ref command
 * @param changed whether publication would change the target ref
 */
public record RepositoryTransferRefUpdate(
    String sourceRef,
    String targetRef,
    ObjectId sourceObjectId,
    ObjectId capturedTargetObjectId,
    ObjectId requiredTargetObjectId,
    boolean changed) {

  /** Validate and defensively copy exact ref IDs. */
  public RepositoryTransferRefUpdate {
    Objects.requireNonNull(sourceRef, "sourceRef");
    Objects.requireNonNull(targetRef, "targetRef");
    Objects.requireNonNull(sourceObjectId, "sourceObjectId");
    Objects.requireNonNull(capturedTargetObjectId, "capturedTargetObjectId");
    Objects.requireNonNull(requiredTargetObjectId, "requiredTargetObjectId");
    sourceObjectId = sourceObjectId.copy();
    capturedTargetObjectId = capturedTargetObjectId.copy();
    requiredTargetObjectId = requiredTargetObjectId.copy();
  }
}
