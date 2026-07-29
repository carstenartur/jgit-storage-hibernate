/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.objects.BoundedInlinePayloadCache.Identity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoundedInlinePayloadCacheTest {

  @Test
  void evictsOldestPayloadWhenHardByteBudgetIsExceeded() {
    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache();
    Identity first = identity("pack-first", "pack", 1L, 256 * 1024);
    Identity second = identity("pack-second", "ref", 2L, 256 * 1024);
    Identity third = identity("pack-third", "pack", 3L, 1);

    cache.put(first, new byte[256 * 1024]);
    cache.put(second, new byte[256 * 1024]);
    cache.put(third, new byte[] {3});

    assertNull(cache.get(first));
    assertEquals(256 * 1024, cache.get(second).length);
    assertArrayEquals(new byte[] {3}, cache.get(third));
    assertEquals(2, cache.entryCount());
    assertEquals(256 * 1024 + 1, cache.retainedBytes());
  }

  @Test
  void ownsDefensiveCopyAndRemovesExactIdentity() {
    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache();
    Identity identity = identity("pack-copy", "pack", 7L, 3);
    byte[] source = {1, 2, 3};
    cache.put(identity, source);
    source[0] = 9;

    assertArrayEquals(new byte[] {1, 2, 3}, cache.get(identity));
    cache.removeAll(List.of(identity));
    assertNull(cache.get(identity));
    assertEquals(0, cache.retainedBytes());
  }

  @Test
  void authoritativeIdentitySetRemovesMetadataMismatch() {
    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache();
    Identity retained = identity("pack-retained", "ref", 8L, 2);
    Identity mismatched = identity("pack-mismatch", "pack", 9L, 2);
    cache.put(retained, new byte[] {1, 2});
    cache.put(mismatched, new byte[] {3, 4});

    cache.retainOnly(
        Set.of(
            retained,
            identity("pack-mismatch", "pack", 10L, 2)));

    assertArrayEquals(new byte[] {1, 2}, cache.get(retained));
    assertNull(cache.get(mismatched));
    assertEquals(1, cache.entryCount());
  }

  @Test
  void rejectsPayloadWhoseSizeDoesNotMatchCommittedIdentity() {
    BoundedInlinePayloadCache cache = new BoundedInlinePayloadCache();
    Identity identity = identity("pack-size", "pack", 11L, 2);

    assertThrows(
        IllegalArgumentException.class,
        () -> cache.put(identity, new byte[] {1, 2, 3}));
  }

  private static Identity identity(
      String packName, String extension, Long rowId, long fileSize) {
    return new Identity(packName, extension, rowId, fileSize);
  }
}
