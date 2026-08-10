/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.List;
import java.util.Objects;

/**
 * Durable-object and exact-ref evidence supplied immediately before atomic ref publication.
 *
 * @param request original immutable transfer request
 * @param refs all requested refs, including idempotent no-op refs
 * @param objectsVisited reachable source objects considered
 * @param objectsTransferred canonical objects inserted into the target
 * @param bytesTransferred canonical object-content bytes inserted into the target
 * @param targetCreated whether this operation created the logical target repository
 */
public record RepositoryTransferPublication(
    RepositoryTransferRequest request,
    List<RepositoryTransferRefUpdate> refs,
    long objectsVisited,
    long objectsTransferred,
    long bytesTransferred,
    boolean targetCreated) {

  /** Validate counters and preserve deterministic request order. */
  public RepositoryTransferPublication {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(refs, "refs");
    refs = List.copyOf(refs);
    if (objectsVisited < 0 || objectsTransferred < 0 || bytesTransferred < 0) {
      throw new IllegalArgumentException("transfer counters must not be negative");
    }
    if (objectsTransferred > objectsVisited) {
      throw new IllegalArgumentException("transferred objects cannot exceed visited objects");
    }
  }
}
