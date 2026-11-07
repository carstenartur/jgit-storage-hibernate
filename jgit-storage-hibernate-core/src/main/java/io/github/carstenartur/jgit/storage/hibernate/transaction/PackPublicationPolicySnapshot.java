/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/**
 * Pack publication diagnostics for one repository handle and its current contention state.
 *
 * @param selector configured publication policy
 * @param lastPath path most recently selected by this handle (which may have subsequently failed)
 * @param lastReason reason for the last selection, or the status when selections are not recorded
 * @param sizeThresholdBytes configured deterministic threshold
 * @param effectiveThresholdBytes current threshold for an eligible, top-level publication
 * @param samples completed publications in the current contention window
 * @param lockAcquisitionEwmaNanos smoothed repository-lock acquisition time, including database RTT
 * @param publicationDurationEwmaNanos smoothed duration from selection through publication
 * @param queuedWritersEwma smoothed count of other local publication writers observed at the lock
 * @param stagedPayloadEwmaBytes smoothed staged logical-pack payload
 */
public record PackPublicationPolicySnapshot(
    String selector,
    Path lastPath,
    Reason lastReason,
    long sizeThresholdBytes,
    long effectiveThresholdBytes,
    int samples,
    long lockAcquisitionEwmaNanos,
    long publicationDurationEwmaNanos,
    double queuedWritersEwma,
    long stagedPayloadEwmaBytes) {

  /** Path chosen for a logical-pack publication. */
  public enum Path {
    /** No selection has been recorded by this handle. */
    NOT_SELECTED,
    /** Persist and publish the pack within one repository-locked transaction. */
    DIRECT,
    /** Persist the invisible payload before the repository-locked publication transaction. */
    PRE_PERSISTED
  }

  /** Eligibility, fallback or contention state behind a selection. */
  public enum Reason {
    /** No selection has been recorded by this handle. */
    NO_SELECTION,
    /** Diagnostics are disabled; the size rule remains in effect. */
    METRICS_DISABLED,
    /** The size rule applies under the configured policy or quiet contention state. */
    SIZE_THRESHOLD,
    /** The logical pack has no chunked extension. */
    INLINE,
    /** The publication replaces existing packs. */
    REPLACEMENT,
    /** At least one expected extension was not staged locally. */
    LEGACY_OR_MIXED,
    /** An enclosing transaction already owns this work; the size rule applies. */
    NESTED_TRANSACTION,
    /** An active, different repository currently owns the stripe; the size rule applies. */
    STRIPE_BUSY,
    /** Too few recent samples; the size rule applies. */
    INSUFFICIENT_SAMPLES,
    /** Repeated contention lowered the threshold for eligible chunked publications. */
    SUSTAINED_CONTENTION
  }
}
