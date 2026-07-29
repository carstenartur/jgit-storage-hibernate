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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.hibernate.SessionFactory;

/** Repository-instance-local LRU for already committed inline pack-extension payloads. */
final class BoundedInlinePayloadCache {

  private final long maxBytes;
  private final LinkedHashMap<Long, byte[]> payloads = new LinkedHashMap<>(16, 0.75f, true);
  private long retainedBytes;

  BoundedInlinePayloadCache(long maxBytes) {
    if (maxBytes < 0) {
      throw new IllegalArgumentException("inline payload cache size must not be negative");
    }
    this.maxBytes = maxBytes;
  }

  static BoundedInlinePayloadCache from(SessionFactory sessionFactory) {
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    Object configured =
        sessionFactory
            .getProperties()
            .get(HibernateStorageSettings.INLINE_PAYLOAD_CACHE_MAX_BYTES);
    long maxBytes =
        configured == null
            ? HibernateStorageSettings.DEFAULT_INLINE_PAYLOAD_CACHE_MAX_BYTES
            : parseMaxBytes(configured);
    return new BoundedInlinePayloadCache(maxBytes);
  }

  private static long parseMaxBytes(Object configured) {
    try {
      long parsed = Long.parseLong(configured.toString());
      if (parsed < 0) {
        throw new IllegalArgumentException(
            HibernateStorageSettings.INLINE_PAYLOAD_CACHE_MAX_BYTES
                + " must not be negative: "
                + configured);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          HibernateStorageSettings.INLINE_PAYLOAD_CACHE_MAX_BYTES
              + " must be a whole number of bytes: "
              + configured,
          exception);
    }
  }

  synchronized byte[] get(Long rowId) {
    return maxBytes == 0 ? null : payloads.get(rowId);
  }

  synchronized void put(Long rowId, byte[] payload) {
    Objects.requireNonNull(rowId, "rowId");
    Objects.requireNonNull(payload, "payload");
    if (maxBytes == 0
        || payload.length > HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD
        || payload.length > maxBytes) {
      return;
    }

    byte[] retained = payload.clone();
    byte[] previous = payloads.put(rowId, retained);
    if (previous != null) {
      retainedBytes -= previous.length;
    }
    retainedBytes += retained.length;
    evictToLimit();
  }

  synchronized void removeAll(Collection<Long> rowIds) {
    for (Long rowId : rowIds) {
      byte[] removed = payloads.remove(rowId);
      if (removed != null) {
        retainedBytes -= removed.length;
      }
    }
  }

  synchronized int entryCount() {
    return payloads.size();
  }

  synchronized long retainedBytes() {
    return retainedBytes;
  }

  private void evictToLimit() {
    Iterator<Map.Entry<Long, byte[]>> iterator = payloads.entrySet().iterator();
    while (retainedBytes > maxBytes && iterator.hasNext()) {
      Map.Entry<Long, byte[]> eldest = iterator.next();
      retainedBytes -= eldest.getValue().length;
      iterator.remove();
    }
  }
}
