/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.objects.PackExtensionStagingBuffer.MemoryBudget;
import io.github.carstenartur.jgit.storage.hibernate.objects.PackExtensionStagingBuffer.StagedPayload;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PackExtensionStagingBufferTest {

  @Test
  void retainsSmallPayloadInBoundedMemoryAndSupportsPositionalReads() throws Exception {
    byte[] data = deterministicBytes(7_321, 17);
    MemoryBudget ownerBudget = new MemoryBudget(64 * 1024);
    AtomicReference<StagedPayload> staged = new AtomicReference<>();
    long processBaseline = PackExtensionStagingBuffer.retainedMemoryBytes();

    PackExtensionStagingBuffer buffer =
        new PackExtensionStagingBuffer(
            ownerBudget, (payload, fileSize, createdAt) -> staged.set(payload));
    buffer.write(data, 0, data.length);
    assertTrue(buffer.memoryBacked());
    ByteBuffer selected = ByteBuffer.allocate(41);
    assertEquals(41, buffer.read(103, selected));
    assertArrayEquals(
        java.util.Arrays.copyOfRange(data, 103, 144), selected.flip().array());

    buffer.close();
    StagedPayload payload = staged.get();
    assertTrue(payload.memoryBacked());
    assertArrayEquals(data, payload.inlineData());
    assertEquals(data.length, ownerBudget.usedBytes());
    assertEquals(processBaseline + data.length, PackExtensionStagingBuffer.retainedMemoryBytes());

    payload.discard();
    payload.discard();
    assertEquals(0, ownerBudget.usedBytes());
    assertEquals(processBaseline, PackExtensionStagingBuffer.retainedMemoryBytes());
  }

  @Test
  void spillsOnceWhenOwnerBudgetIsExhaustedAndPreservesReadSemantics() throws Exception {
    byte[] first = deterministicBytes(900, 23);
    byte[] second = deterministicBytes(300, 29);
    byte[] expected = new byte[first.length + second.length];
    System.arraycopy(first, 0, expected, 0, first.length);
    System.arraycopy(second, 0, expected, first.length, second.length);
    MemoryBudget ownerBudget = new MemoryBudget(1_024);
    AtomicReference<StagedPayload> staged = new AtomicReference<>();
    long processBaseline = PackExtensionStagingBuffer.retainedMemoryBytes();

    PackExtensionStagingBuffer buffer =
        new PackExtensionStagingBuffer(
            ownerBudget, (payload, fileSize, createdAt) -> staged.set(payload));
    buffer.write(first, 0, first.length);
    assertTrue(buffer.memoryBacked());
    assertEquals(1_024, ownerBudget.usedBytes());
    buffer.write(second, 0, second.length);
    assertFalse(buffer.memoryBacked());
    assertEquals(0, ownerBudget.usedBytes());
    assertEquals(processBaseline, PackExtensionStagingBuffer.retainedMemoryBytes());

    ByteBuffer selected = ByteBuffer.allocate(240);
    assertEquals(240, buffer.read(840, selected));
    assertArrayEquals(
        java.util.Arrays.copyOfRange(expected, 840, 1_080), selected.flip().array());
    buffer.close();

    StagedPayload payload = staged.get();
    assertFalse(payload.memoryBacked());
    assertArrayEquals(expected, readAll(payload, expected.length));
    payload.discard();
    payload.discard();
  }

  @Test
  void spillsWhenStagingMemoryLimitIsCrossedAndRejectsClosedAccess() throws Exception {
    byte[] data =
        deterministicBytes(PackExtensionStagingBuffer.MAX_MEMORY_BYTES + 37, 31);
    AtomicReference<StagedPayload> staged = new AtomicReference<>();
    PackExtensionStagingBuffer buffer =
        new PackExtensionStagingBuffer(
            new MemoryBudget(PackExtensionStagingBuffer.MAX_MEMORY_BYTES),
            (payload, fileSize, createdAt) -> staged.set(payload));

    buffer.write(data, 0, PackExtensionStagingBuffer.MAX_MEMORY_BYTES - 11);
    assertTrue(buffer.memoryBacked());
    buffer.write(
        data,
        PackExtensionStagingBuffer.MAX_MEMORY_BYTES - 11,
        data.length - PackExtensionStagingBuffer.MAX_MEMORY_BYTES + 11);
    assertFalse(buffer.memoryBacked());
    buffer.close();
    buffer.close();

    assertThrows(java.io.IOException.class, buffer::flush);
    assertThrows(
        java.io.IOException.class, () -> buffer.read(0, ByteBuffer.allocate(1)));
    assertThrows(
        java.io.IOException.class, () -> buffer.write(new byte[] {1}, 0, 1));
    assertArrayEquals(data, readAll(staged.get(), data.length));
    staged.get().discard();
  }

  @Test
  void memoryBudgetIsBoundedAndRejectsOverRelease() {
    MemoryBudget budget = new MemoryBudget(10);
    assertTrue(budget.tryReserve(6));
    assertFalse(budget.tryReserve(5));
    assertTrue(budget.tryReserve(4));
    assertEquals(10, budget.usedBytes());
    budget.release(10);
    assertEquals(0, budget.usedBytes());
    assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(-1));
    assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
    assertThrows(IllegalStateException.class, () -> budget.release(1));
    assertThrows(IllegalArgumentException.class, () -> new MemoryBudget(-1));
  }

  private static byte[] readAll(StagedPayload payload, int size) throws Exception {
    byte[] data = new byte[size];
    ByteBuffer destination = ByteBuffer.wrap(data);
    try (PackExtensionStagingBuffer.StagedPayloadReader reader = payload.openReader()) {
      long position = 0;
      while (destination.hasRemaining()) {
        int count = reader.read(position, destination);
        if (count <= 0) {
          throw new AssertionError("Staged payload ended early");
        }
        position += count;
      }
    }
    return data;
  }

  private static byte[] deterministicBytes(int length, int seed) {
    byte[] result = new byte[length];
    int value = seed;
    for (int index = 0; index < result.length; index++) {
      value = value * 1103515245 + 12345;
      result[index] = (byte) (value >>> 16);
    }
    return result;
  }
}
