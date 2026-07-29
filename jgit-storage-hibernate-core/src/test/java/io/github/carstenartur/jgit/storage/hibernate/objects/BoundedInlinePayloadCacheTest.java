/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedInlinePayloadCacheTest {

  @Test
  void evictsLeastRecentlyUsedPayloadByBytes() {
    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache(6);
    cache.put(1L, new byte[] {1, 1, 1});
    cache.put(2L, new byte[] {2, 2, 2});

    assertArrayEquals(new byte[] {1, 1, 1}, cache.get(1L));
    cache.put(3L, new byte[] {3, 3, 3});

    assertArrayEquals(new byte[] {1, 1, 1}, cache.get(1L));
    assertNull(cache.get(2L));
    assertArrayEquals(new byte[] {3, 3, 3}, cache.get(3L));
    assertEquals(2, cache.entryCount());
    assertEquals(6, cache.retainedBytes());
  }

  @Test
  void ownsDefensiveCopyAndRemovesRows() {
    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache(16);
    byte[] source = {1, 2, 3};
    cache.put(7L, source);
    source[0] = 9;

    assertArrayEquals(new byte[] {1, 2, 3}, cache.get(7L));
    cache.removeAll(List.of(7L));
    assertNull(cache.get(7L));
    assertEquals(0, cache.retainedBytes());
  }

  @Test
  void zeroDisablesAndOversizedEntriesAreRejected() {
    BoundedInlinePayloadCache disabled = new BoundedInlinePayloadCache(0);
    disabled.put(1L, new byte[] {1});
    assertNull(disabled.get(1L));

    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache(2);
    cache.put(2L, new byte[] {1, 2, 3});
    assertNull(cache.get(2L));
  }

  @Test
  void rejectsNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new BoundedInlinePayloadCache(-1));
  }
}
