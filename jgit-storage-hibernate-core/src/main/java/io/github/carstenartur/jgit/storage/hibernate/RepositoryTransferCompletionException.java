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

/**
 * Signals that Git objects and refs were committed but a completion participant failed afterwards.
 *
 * <p>The embedded result is authoritative. Retrying the same transfer is safe and normally yields
 * an idempotent no-op that invokes completion participants again.
 */
public final class RepositoryTransferCompletionException extends HibernateStorageException {

  private final RepositoryTransferResult result;

  /**
   * Create a post-commit callback failure.
   *
   * @param result already committed transfer result
   * @param cause participant failure
   */
  public RepositoryTransferCompletionException(
      RepositoryTransferResult result, RuntimeException cause) {
    super(
        "Repository transfer committed, but a completion participant failed for target "
            + Objects.requireNonNull(result, "result").target(),
        Objects.requireNonNull(cause, "cause"));
    this.result = result;
  }

  /** @return authoritative committed transfer result */
  public RepositoryTransferResult result() {
    return result;
  }
}
