/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import java.util.Objects;

/** Durable outcome for one idempotent queryable reflog append command. */
public record ReflogAppendResult(String deliveryId, Status status) {

  /** Whether this transaction created the row or recognized an exact committed replay. */
  public enum Status {
    /** The command was appended by the transaction that produced this result. */
    APPENDED,
    /** An identical command with the same delivery ID was already committed. */
    ALREADY_APPLIED
  }

  /** Validate the durable result. */
  public ReflogAppendResult {
    Objects.requireNonNull(deliveryId, "deliveryId");
    Objects.requireNonNull(status, "status");
  }
}
