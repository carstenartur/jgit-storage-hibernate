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

/**
 * Observable result of one logical repository transfer.
 *
 * @param source source logical repository
 * @param target target logical repository
 * @param objectsVisited reachable source objects considered
 * @param objectsTransferred objects not already present in the target
 * @param bytesTransferred canonical object-content bytes inserted into the target
 * @param refs target-ref keyed publication evidence
 * @param targetCreated whether the operation created the logical target repository
 * @param noOp whether no object or ref change was required
 */
public record RepositoryTransferResult(
    RepositoryName source,
    RepositoryName target,
    long objectsVisited,
    long objectsTransferred,
    long bytesTransferred,
    Map<String, RefTransferResult> refs,
    boolean targetCreated,
    boolean noOp) {

  /** Validate counts and preserve deterministic ref order. */
  public RepositoryTransferResult {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(refs, "refs");
    if (objectsVisited < 0 || objectsTransferred < 0 || bytesTransferred < 0) {
      throw new IllegalArgumentException("transfer counters must not be negative");
    }
    if (objectsTransferred > objectsVisited) {
      throw new IllegalArgumentException("transferred objects cannot exceed visited objects");
    }
    refs = Collections.unmodifiableMap(new LinkedHashMap<>(refs));
  }
}
