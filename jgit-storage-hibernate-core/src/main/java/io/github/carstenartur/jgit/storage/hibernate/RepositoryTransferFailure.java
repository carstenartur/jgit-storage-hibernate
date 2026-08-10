/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Best-effort failure notification for audit, cleanup and projection-staleness handling.
 *
 * @param request original immutable transfer request
 * @param stage last authoritative transfer stage entered
 * @param sourceObjectIds exact captured source IDs keyed by source ref; empty before snapshot
 * @param targetCreated whether the failed operation created the logical target repository
 * @param cause primary transfer failure
 */
public record RepositoryTransferFailure(
    RepositoryTransferRequest request,
    RepositoryTransferStage stage,
    Map<String, ObjectId> sourceObjectIds,
    boolean targetCreated,
    Throwable cause) {

  /** Validate and defensively copy failure evidence. */
  public RepositoryTransferFailure {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(sourceObjectIds, "sourceObjectIds");
    Objects.requireNonNull(cause, "cause");
    Map<String, ObjectId> copied = new LinkedHashMap<>();
    sourceObjectIds.forEach(
        (ref, objectId) -> {
          Objects.requireNonNull(ref, "source ref");
          Objects.requireNonNull(objectId, "source object ID");
          copied.put(ref, objectId.copy());
        });
    sourceObjectIds = Collections.unmodifiableMap(copied);
  }
}
