/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hard-bounded repository-instance handoff for locally published inline PACK and Reftable bytes.
 *
 * <p>Entries are keyed by their complete immutable committed identity. Database-loaded arrays are
 * never inserted. Authoritative catalog scans retain an entry only while every identity component
 * still matches the committed row.
 */
final class BoundedInlinePayloadCache {

  static final long MAX_RETAINED_BYTES = 512L * 1024;

  private final LinkedHashMap<Identity, byte[]> payloads = new LinkedHashMap<>();
  private long retainedBytes;

  synchronized byte[] get(Identity identity) {
    return payloads.get(identity);
  }

  synchronized void put(Identity identity, byte[] payload) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(payload, "payload");
    if (payload.length != identity.fileSize()) {
      throw new IllegalArgumentException(
          "inline payload size does not match committed identity: "
              + payload.length
              + " != "
              + identity.fileSize());
    }
    if (payload.length > HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD
        || payload.length > MAX_RETAINED_BYTES) {
      return;
    }

    byte[] retained = payload.clone();
    byte[] previous = payloads.remove(identity);
    if (previous != null) {
      retainedBytes -= previous.length;
    }
    payloads.put(identity, retained);
    retainedBytes += retained.length;
    evictOldestToLimit();
  }

  synchronized void retainOnly(Set<Identity> committedIdentities) {
    Iterator<Map.Entry<Identity, byte[]>> iterator = payloads.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<Identity, byte[]> entry = iterator.next();
      if (!committedIdentities.contains(entry.getKey())) {
        retainedBytes -= entry.getValue().length;
        iterator.remove();
      }
    }
  }

  synchronized void removeAll(Collection<Identity> identities) {
    for (Identity identity : identities) {
      byte[] removed = payloads.remove(identity);
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

  private void evictOldestToLimit() {
    Iterator<Map.Entry<Identity, byte[]>> iterator = payloads.entrySet().iterator();
    while (retainedBytes > MAX_RETAINED_BYTES && iterator.hasNext()) {
      Map.Entry<Identity, byte[]> oldest = iterator.next();
      retainedBytes -= oldest.getValue().length;
      iterator.remove();
    }
  }

  record Identity(String packName, String extension, Long rowId, long fileSize) {
    Identity {
      Objects.requireNonNull(packName, "packName");
      Objects.requireNonNull(extension, "extension");
      Objects.requireNonNull(rowId, "rowId");
      if (fileSize < 0) {
        throw new IllegalArgumentException("fileSize must not be negative");
      }
    }
  }
}
