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
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
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

  private final Session parentSession;
  private final int batchSize;
  private final long statelessMinPayloadBytes;
  private final List<GitPackChunkEntity> statelessPending;
  private Selection selection;
  private StatelessSession statelessSession;
  private int pendingCount;
  private boolean failed;
  private boolean closed;

  private HibernatePackChunkWriter(
      Session parentSession,
      int batchSize,
      long statelessMinPayloadBytes,
      Selection selection) {
    this.parentSession = parentSession;
    this.batchSize = batchSize;
    this.statelessMinPayloadBytes = statelessMinPayloadBytes;
    this.selection = selection;
    this.statelessPending = new ArrayList<>(batchSize);
    if (selection == Selection.STATELESS) {
      openStatelessSession();
    }
  }

  /**
   * Open a writer whose automatic mode resolves the payload size from the managed parent row.
   *
   * <p>The parent pack was persisted and flushed immediately before this call. On the first chunk,
   * auto mode retrieves that already managed parent by identifier, so Hibernate normally serves the
   * file size from the first-level persistence context without another SQL round trip.
   */
  static HibernatePackChunkWriter open(Session parentSession) {
    return open(parentSession, null);
  }

  /**
   * Open the configured writer with an already known complete staged extension size.
   *
   * @param parentSession active parent Hibernate session
   * @param payloadBytes complete staged extension size, or {@code null} to resolve from the parent
   */
  static HibernatePackChunkWriter open(Session parentSession, Long payloadBytes) {
    Objects.requireNonNull(parentSession, "parentSession");
    if (payloadBytes != null && payloadBytes < 0) {
      throw new IllegalArgumentException("payloadBytes must not be negative");
    }
    Map<String, Object> properties = parentSession.getSessionFactory().getProperties();
    int batchSize = HibernateStorageSettings.resolvePackChunkBatchSize(properties);
    long threshold = HibernateStorageSettings.resolveStatelessMinPayloadBytes(properties);
    String mode = HibernateStorageSettings.resolvePackChunkWriter(properties);
    Selection selection =
        switch (mode) {
          case STATEFUL_MODE -> Selection.STATEFUL;
          case STATELESS_MODE -> Selection.STATELESS;
          case AUTO_MODE ->
              payloadBytes == null
                  ? Selection.UNDECIDED
                  : selectionForPayload(payloadBytes, threshold);
          default -> throw new IllegalStateException("Validated chunk writer mode was " + mode);
        };
    return new HibernatePackChunkWriter(parentSession, batchSize, threshold, selection);
  }

  void insert(List<GitPackChunkEntity> chunks) {
    Objects.requireNonNull(chunks, "chunks");
    ensureOpen();
    if (chunks.isEmpty()) {
      return;
    }
    try {
      resolveSelection(chunks.getFirst());
      for (GitPackChunkEntity chunk : chunks) {
        insertOne(Objects.requireNonNull(chunk, "chunk"));
      }
    } catch (RuntimeException exception) {
      failed = true;
      throw exception;
    }
  }

  boolean stateless() {
    return selection == Selection.STATELESS;
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

  private void resolveSelection(GitPackChunkEntity firstChunk) {
    if (selection != Selection.UNDECIDED) {
      return;
    }
    GitPackEntity parent = parentSession.find(GitPackEntity.class, firstChunk.getPackId());
    if (parent == null) {
      throw new IllegalStateException(
          "Cannot resolve automatic chunk writer for missing pack " + firstChunk.getPackId());
    }
    selection = selectionForPayload(parent.getFileSize(), statelessMinPayloadBytes);
    if (selection == Selection.STATELESS) {
      openStatelessSession();
    }
  }

  private void openStatelessSession() {
    parentSession.clear();
    statelessSession =
        parentSession
            .statelessWithOptions()
            .connection()
            .initialCacheMode(CacheMode.IGNORE)
            .open();
  }

  private void insertOne(GitPackChunkEntity chunk) {
    if (selection == Selection.STATELESS) {
      statelessPending.add(chunk);
    } else if (selection == Selection.STATEFUL) {
      parentSession.persist(chunk);
    } else {
      throw new IllegalStateException("Chunk writer selection was not resolved");
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
    if (selection == Selection.STATELESS) {
      statelessSession.insertMultiple(List.copyOf(statelessPending));
      statelessPending.clear();
    } else if (selection == Selection.STATEFUL) {
      parentSession.flush();
      parentSession.clear();
    } else {
      throw new IllegalStateException("Chunk writer selection was not resolved");
    }
    pendingCount = 0;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("Pack chunk writer is closed");
    }
  }

  private static Selection selectionForPayload(long payloadBytes, long threshold) {
    return payloadBytes >= threshold ? Selection.STATELESS : Selection.STATEFUL;
  }

  private enum Selection {
    UNDECIDED,
    STATEFUL,
    STATELESS
  }
}
