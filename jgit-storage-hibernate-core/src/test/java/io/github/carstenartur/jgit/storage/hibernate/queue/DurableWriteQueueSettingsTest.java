/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableWriteQueueSettingsTest {

  @Test
  void defaultsPersistAtMostFiftyRecordsAfterTwoMilliseconds() {
    DurableStripedWriteQueue.Limits limits = DurableWriteQueueSettings.from(Map.of());

    assertEquals(4, limits.stripes());
    assertEquals(50, limits.maxBatchCommands());
    assertEquals(Duration.ofMillis(2), limits.maxBatchWait());
  }

  @Test
  void applicationCanConfigureCountBytesAndCollectionWindowIndependently() {
    DurableStripedWriteQueue.Limits limits =
        DurableWriteQueueSettings.from(
            Map.of(
                DurableWriteQueueSettings.STRIPES, "8",
                DurableWriteQueueSettings.MAX_BATCH_COMMANDS, "25",
                DurableWriteQueueSettings.MAX_BATCH_BYTES, "1048576",
                DurableWriteQueueSettings.MAX_BATCH_WAIT_MILLIS, "7",
                DurableWriteQueueSettings.ENQUEUE_TIMEOUT_MILLIS, "250"));

    assertEquals(8, limits.stripes());
    assertEquals(25, limits.maxBatchCommands());
    assertEquals(1_048_576L, limits.maxBatchBytes());
    assertEquals(Duration.ofMillis(7), limits.maxBatchWait());
    assertEquals(Duration.ofMillis(250), limits.enqueueTimeout());
  }

  @Test
  void validationRejectsABatchLargerThanTheBoundedQueue() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DurableWriteQueueSettings.from(
                Map.of(
                    DurableWriteQueueSettings.MAX_QUEUED_COMMANDS_PER_STRIPE,
                    "10",
                    DurableWriteQueueSettings.MAX_BATCH_COMMANDS,
                    "50")));
  }
}
