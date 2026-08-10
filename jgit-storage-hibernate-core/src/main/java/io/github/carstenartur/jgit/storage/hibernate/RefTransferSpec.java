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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Maps one exact source ref to one exact target ref.
 *
 * @param sourceRef fully qualified source ref under {@code refs/heads/} or {@code refs/tags/}
 * @param targetRef fully qualified target ref under {@code refs/heads/} or {@code refs/tags/}
 * @param expectedTargetObjectId required current target ID for compare-and-set, optional stale-writer
 *     guard for force, or null for create-only and fast-forward-only transfers
 */
public record RefTransferSpec(
    String sourceRef, String targetRef, ObjectId expectedTargetObjectId) {

  /** Validate and defensively copy one ref mapping. */
  public RefTransferSpec {
    sourceRef = validateRefName(sourceRef, "sourceRef");
    targetRef = validateRefName(targetRef, "targetRef");
    if (expectedTargetObjectId != null) {
      expectedTargetObjectId = expectedTargetObjectId.copy();
    }
  }

  /**
   * Create a ref mapping without an expected current target value.
   *
   * @param sourceRef fully qualified source ref
   * @param targetRef fully qualified target ref
   */
  public RefTransferSpec(String sourceRef, String targetRef) {
    this(sourceRef, targetRef, null);
  }

  private static String validateRefName(String value, String component) {
    Objects.requireNonNull(value, component);
    if (!Repository.isValidRefName(value)) {
      throw new IllegalArgumentException(component + " is not a valid Git ref name: " + value);
    }
    if (!value.startsWith(Constants.R_HEADS) && !value.startsWith(Constants.R_TAGS)) {
      throw new IllegalArgumentException(
          component + " must be under refs/heads/ or refs/tags/: " + value);
    }
    return value;
  }
}
