/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import java.io.IOException;
import java.util.Objects;

/**
 * Deterministic semantic rejection of a complete reflog batch.
 *
 * <p>The owning transaction is rolled back and retrying the unchanged command cannot succeed. This
 * is distinct from an infrastructure failure whose commit outcome may be unknown and which is safe
 * to replay with the same delivery IDs.
 */
public final class ReflogBatchRejectedException extends IOException {

  /** Stable reason that callers can use for dead-letter or reconciliation handling. */
  public enum Reason {
    /** One in-memory batch repeated the same delivery ID. */
    DUPLICATE_DELIVERY_ID_IN_BATCH,
    /** A committed delivery ID was reused with different immutable content. */
    DELIVERY_ID_REUSED_WITH_DIFFERENT_PAYLOAD,
    /** More than one committed row exists for an allegedly idempotent delivery ID. */
    DUPLICATE_PERSISTED_DELIVERY_ID,
    /** A new entry did not continue the latest committed queryable reflog chain. */
    NON_CONTIGUOUS_REF_HISTORY
  }

  private final Reason reason;
  private final String deliveryId;
  private final String refName;

  /** Create a semantic batch rejection. */
  public ReflogBatchRejectedException(
      Reason reason, String deliveryId, String refName, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
    this.deliveryId = deliveryId;
    this.refName = refName;
  }

  public Reason reason() {
    return reason;
  }

  public String deliveryId() {
    return deliveryId;
  }

  public String refName() {
    return refName;
  }
}
