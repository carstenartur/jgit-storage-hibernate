/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.CacheMode;
import org.hibernate.Session;
import org.hibernate.StatelessSession;

/**
 * Internal insertion strategy for immutable pack payload chunks.
 *
 * <p>The default automatic path keeps ordinary chunked publications stateful and selects a child
 * {@link StatelessSession} at the configured large-payload threshold. The child shares the parent
 * session's JDBC connection and resource-local transaction. Only non-indexed raw chunk rows pass
 * through this class; searchable projections continue to use ordinary stateful sessions.
 *
 * <p>Calls from the staging reader may supply smaller groups than the configured writer batch. This
 * class therefore retains at most the configured number of one-MiB chunks and flushes them together.
 * That keeps memory bounded while allowing deployments with measurable database RTT to reduce
 * sequential JDBC batch executions.
 */
final class HibernatePackChunkWriter implements AutoCloseable {

  static final String MODE_PROPERTY = HibernateStorageSettings.PACK_CHUNK_WRITER;
  static final String AUTO_MODE = HibernateStorageSettings.AUTO_CHUNK_WRITER;
  static final String STATEFUL_MODE = HibernateStorageSettings.STATEFUL_CHUNK_WRITER;
  static final String STATELESS_MODE = HibernateStorageSettings.STATELESS_CHUNK_WRITER;

  private final Session statefulSession;
  private final StatelessSession statelessSession;
  private final int batchSize;
  private final List<GitPackChunkEntity> statelessPending;
  private int pendingCount;
  private boolean failed;
  private boolean closed;

  private HibernatePackChunkWriter(
      Session statefulSession, StatelessSession statelessSession, int batchSize) {
    this.statefulSession = statefulSession;
    this.statelessSession = statelessSession;
    this.batchSize = batchSize;
    this.statelessPending = new ArrayList<>(batchSize);
  }

  /** Open a writer for an unspecified small payload; retained for focused internal tests. */
  static HibernatePackChunkWriter open(Session parentSession) {
    return open(parentSession, 0L);
  }

  /**
   * Open the configured writer for a parent session whose pending parent insert was flushed.
   *
   * <p>In {@code auto} mode, payloads at or above
   * {@link HibernateStorageSettings#STATELESS_MIN_PAYLOAD_BYTES} use stateless insertion. The
   * stateless path clears the parent persistence context before opening the child so parent and child
   * never manage the same entity instance. Calling {@code connection()} on the child builder keeps
   * parent, chunks and any later publication mutation in the same JDBC transaction.
   *
   * @param parentSession active parent Hibernate session
   * @param payloadBytes complete staged extension size used by automatic selection
   */
  static HibernatePackChunkWriter open(Session parentSession, long payloadBytes) {
    Objects.requireNonNull(parentSession, "parentSession");
    if (payloadBytes < 0) {
      throw new IllegalArgumentException("payloadBytes must not be negative");
    }
    Map<String, Object> properties = parentSession.getSessionFactory().getProperties();
    int batchSize = HibernateStorageSettings.resolvePackChunkBatchSize(properties);
    if (useStateless(properties, payloadBytes)) {
      parentSession.clear();
      StatelessSession child =
          parentSession
              .statelessWithOptions()
              .connection()
              .initialCacheMode(CacheMode.IGNORE)
              .open();
      return new HibernatePackChunkWriter(null, child, batchSize);
    }
    return new HibernatePackChunkWriter(parentSession, null, batchSize);
  }

  void insert(List<GitPackChunkEntity> chunks) {
    Objects.requireNonNull(chunks, "chunks");
    ensureOpen();
    if (chunks.isEmpty()) {
      return;
    }
    try {
      for (GitPackChunkEntity chunk : chunks) {
        insertOne(Objects.requireNonNull(chunk, "chunk"));
      }
    } catch (RuntimeException exception) {
      failed = true;
      throw exception;
    }
  }

  boolean stateless() {
    return statelessSession != null;
  }

  int batchSize() {
    return batchSize;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (!failed) {
        flushPending();
      }
    } finally {
      statelessPending.clear();
      pendingCount = 0;
      if (statelessSession != null) {
        statelessSession.close();
      }
    }
  }

  private void insertOne(GitPackChunkEntity chunk) {
    if (statelessSession != null) {
      statelessPending.add(chunk);
    } else {
      statefulSession.persist(chunk);
    }
    pendingCount++;
    if (pendingCount == batchSize) {
      flushPending();
    }
  }

  private void flushPending() {
    if (pendingCount == 0) {
      return;
    }
    if (statelessSession != null) {
      statelessSession.insertMultiple(List.copyOf(statelessPending));
      statelessPending.clear();
    } else {
      statefulSession.flush();
      statefulSession.clear();
    }
    pendingCount = 0;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("Pack chunk writer is closed");
    }
  }

  private static boolean useStateless(Map<?, ?> properties, long payloadBytes) {
    String mode = HibernateStorageSettings.resolvePackChunkWriter(properties);
    return switch (mode) {
      case STATEFUL_MODE -> false;
      case STATELESS_MODE -> true;
      case AUTO_MODE ->
          payloadBytes >= HibernateStorageSettings.resolveStatelessMinPayloadBytes(properties);
      default -> throw new IllegalStateException("Validated chunk writer mode was " + mode);
    };
  }
}
