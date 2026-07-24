/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import org.hibernate.Session;

/**
 * Participates in transactional deletion of one logical repository.
 *
 * <p>Optional modules use this SPI to remove repository-scoped projections in the same transaction
 * as core pack and reflog data. Throwing a runtime exception rolls the complete deletion back.
 */
@FunctionalInterface
public interface RepositoryDeletionParticipant {

  /**
   * Delete optional state for one repository using the supplied active session.
   *
   * @param session active Hibernate session and transaction
   * @param repositoryName logical repository being deleted
   * @return number of projection rows removed
   */
  int deleteRepository(Session session, RepositoryName repositoryName);
}
