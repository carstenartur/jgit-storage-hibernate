/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.queue;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Resolves bounded durable write-queue limits from application properties. */
public final class DurableWriteQueueSettings {

  public static final String PREFIX = "jgit.storage.hibernate.write_queue.";
  public static final String STRIPES = PREFIX + "stripes";
  public static final String MAX_QUEUED_COMMANDS_PER_STRIPE =
      PREFIX + "max_queued_commands_per_stripe";
  public static final String MAX_QUEUED_BYTES_PER_STRIPE =
      PREFIX + "max_queued_bytes_per_stripe";
  public static final String MAX_BATCH_COMMANDS = PREFIX + "max_batch_commands";
  public static final String MAX_BATCH_BYTES = PREFIX + "max_batch_bytes";
  public static final String MAX_BATCH_WAIT_MILLIS = PREFIX + "max_batch_wait_ms";
  public static final String ENQUEUE_TIMEOUT_MILLIS = PREFIX + "enqueue_timeout_ms";

  public static final int DEFAULT_STRIPES = 4;

  private DurableWriteQueueSettings() {}

  /** Resolve production defaults plus any supplied overrides. */
  public static DurableStripedWriteQueue.Limits from(Map<?, ?> properties) {
    return from(properties, DEFAULT_STRIPES);
  }

  /** Resolve production defaults plus any supplied overrides and caller-selected stripe default. */
  public static DurableStripedWriteQueue.Limits from(
      Map<?, ?> properties, int defaultStripes) {
    Objects.requireNonNull(properties, "properties");
    DurableStripedWriteQueue.Limits defaults =
        DurableStripedWriteQueue.Limits.productionDefaults(defaultStripes);
    return new DurableStripedWriteQueue.Limits(
        integer(properties, STRIPES, defaults.stripes()),
        integer(
            properties,
            MAX_QUEUED_COMMANDS_PER_STRIPE,
            defaults.maxQueuedCommandsPerStripe()),
        longValue(
            properties,
            MAX_QUEUED_BYTES_PER_STRIPE,
            defaults.maxQueuedBytesPerStripe()),
        integer(properties, MAX_BATCH_COMMANDS, defaults.maxBatchCommands()),
        longValue(properties, MAX_BATCH_BYTES, defaults.maxBatchBytes()),
        Duration.ofMillis(
            longValue(
                properties,
                MAX_BATCH_WAIT_MILLIS,
                defaults.maxBatchWait().toMillis())),
        Duration.ofMillis(
            longValue(
                properties,
                ENQUEUE_TIMEOUT_MILLIS,
                defaults.enqueueTimeout().toMillis())));
  }

  private static int integer(Map<?, ?> properties, String name, int defaultValue) {
    long value = longValue(properties, name, defaultValue);
    if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
      throw new IllegalArgumentException(name + " exceeds the integer range: " + value);
    }
    return (int) value;
  }

  private static long longValue(Map<?, ?> properties, String name, long defaultValue) {
    Object configured = properties.get(name);
    if (configured == null || configured.toString().isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(configured.toString().trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          name + " must be an integer but was '" + configured + "'", exception);
    }
  }
}
