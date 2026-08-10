/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/**
 * Transfers authoritative Git objects and selected refs between logical Hibernate repositories.
 *
 * <p>The service copies Git history, not application projection tables or search indexes. A
 * successful transfer therefore preserves object IDs and ancestry while optional projections can
 * be rebuilt from the target repository afterwards.
 */
public interface HibernateRepositoryTransferService {

  /**
   * Transfer one immutable source-ref snapshot into another logical repository.
   *
   * @param request transfer plan
   * @return transfer evidence including exact source and target object IDs
   */
  default RepositoryTransferResult transfer(RepositoryTransferRequest request) {
    throw new UnsupportedOperationException(
        "Logical repository transfer is not supported by this implementation");
  }
}
