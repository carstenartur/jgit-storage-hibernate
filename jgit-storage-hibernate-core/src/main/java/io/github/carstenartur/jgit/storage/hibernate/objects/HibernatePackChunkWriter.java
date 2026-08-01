/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.CacheMode;
import org.hibernate.Session;
import org.hibernate.StatelessSession;

/**
 * Internal insertion strategy for immutable pack payload chunks.
 *
 * <p>The default stateful path preserves the established persistence-context batching behavior. The
 * experimental stateless path opens a child session that shares the parent session's JDBC
 * connection and resource-local transaction. Only non-indexed raw chunk rows pass through this
 * class; searchable projections continue to use ordinary stateful sessions.
 */
final class HibernatePackChunkWriter implements AutoCloseable {

  static final String MODE_PROPERTY = "jgit.storage.hibernate.pack.chunk_writer";
  static final String STATEFUL_MODE = "stateful";
  static final String STATELESS_MODE = "stateless";

  private final Session statefulSession;
  private final StatelessSession statelessSession;

  private HibernatePackChunkWriter(Session statefulSession, StatelessSession statelessSession) {
    this.statefulSession = statefulSession;
    this.statelessSession = statelessSession;
  }

  /**
   * Open the configured writer for a parent session whose pending parent insert was flushed.
   *
   * <p>The stateless path clears the parent persistence context before opening the child so parent
   * and child never manage the same entity instance. Calling {@code connection()} on the child
   * builder keeps parent, chunks and any later publication mutation in the same JDBC transaction.
   */
  static HibernatePackChunkWriter open(Session parentSession) {
    Objects.requireNonNull(parentSession, "parentSession");
    if (configuredMode(parentSession) == Mode.STATELESS) {
      parentSession.clear();
      StatelessSession child =
          parentSession
              .statelessWithOptions()
              .connection()
              .initialCacheMode(CacheMode.IGNORE)
              .open();
      return new HibernatePackChunkWriter(null, child);
    }
    return new HibernatePackChunkWriter(parentSession, null);
  }

  void insert(List<GitPackChunkEntity> chunks) {
    Objects.requireNonNull(chunks, "chunks");
    if (chunks.isEmpty()) {
      return;
    }
    if (statelessSession != null) {
      statelessSession.insertMultiple(List.copyOf(chunks));
      return;
    }
    for (GitPackChunkEntity chunk : chunks) {
      statefulSession.persist(chunk);
    }
    statefulSession.flush();
    statefulSession.clear();
  }

  boolean stateless() {
    return statelessSession != null;
  }

  @Override
  public void close() {
    if (statelessSession != null) {
      statelessSession.close();
    }
  }

  private static Mode configuredMode(Session session) {
    Object configured = session.getSessionFactory().getProperties().get(MODE_PROPERTY);
    if (configured == null || configured.toString().isBlank()) {
      return Mode.STATEFUL;
    }
    return switch (configured.toString().trim().toLowerCase(Locale.ROOT)) {
      case STATEFUL_MODE -> Mode.STATEFUL;
      case STATELESS_MODE -> Mode.STATELESS;
      default ->
          throw new IllegalArgumentException(
              "Unsupported "
                  + MODE_PROPERTY
                  + " value '"
                  + configured
                  + "'; expected '"
                  + STATEFUL_MODE
                  + "' or '"
                  + STATELESS_MODE
                  + "'");
    };
  }

  private enum Mode {
    STATEFUL,
    STATELESS
  }
}
