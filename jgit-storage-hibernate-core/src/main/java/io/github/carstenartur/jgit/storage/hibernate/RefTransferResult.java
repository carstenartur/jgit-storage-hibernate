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
 * Evidence for one published target ref.
 *
 * @param sourceRef source ref captured at transfer start
 * @param targetRef published target ref
 * @param sourceObjectId exact source snapshot object ID
 * @param targetObjectId exact final target object ID
 */
public record RefTransferResult(
    String sourceRef, String targetRef, ObjectId sourceObjectId, ObjectId targetObjectId) {

  /** Validate immutable ref evidence. */
  public RefTransferResult {
    Objects.requireNonNull(sourceRef, "sourceRef");
    Objects.requireNonNull(targetRef, "targetRef");
    Objects.requireNonNull(sourceObjectId, "sourceObjectId");
    Objects.requireNonNull(targetObjectId, "targetObjectId");
    sourceObjectId = sourceObjectId.copy();
    targetObjectId = targetObjectId.copy();
  }
}
