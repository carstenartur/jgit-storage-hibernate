/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot.Reason;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PackPublicationSelectorTest {

  private static final long ONE_MIB = 1024 * 1024;
  private static final long SMALL_PACK = 768 * 1024;
  private static final long TICK = TimeUnit.MILLISECONDS.toNanos(100);
  private static final long LONG_LOCK_WAIT = TimeUnit.MILLISECONDS.toNanos(20);
  private static final long PUBLICATION_DURATION = TimeUnit.MILLISECONDS.toNanos(40);

  @Test
  void requiresSustainedContentionAndAStillLongerQuietInterval() {
    PackPublicationSelector selector = selector(UUID.randomUUID().toString(), "repo");
    assertFalse(select(selector, null, SMALL_PACK, 0));
    assertTrue(select(selector, null, ONE_MIB, 0));

    sample(selector, 1, true, LONG_LOCK_WAIT);
    sample(selector, 2, true, LONG_LOCK_WAIT);
    assertFalse(select(selector, null, SMALL_PACK, 3));
    assertEquals(Reason.INSUFFICIENT_SAMPLES, selector.snapshot(3 * TICK).lastReason());

    sample(selector, 3, true, LONG_LOCK_WAIT);
    assertTrue(select(selector, null, SMALL_PACK, 4));
    assertEquals(Reason.SUSTAINED_CONTENTION, selector.snapshot(4 * TICK).lastReason());
    assertEquals(
        HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 1L,
        selector.snapshot(4 * TICK).effectiveThresholdBytes());
    assertEquals(SMALL_PACK, selector.snapshot(4 * TICK).stagedPayloadEwmaBytes());

    for (Reason exclusion :
        new Reason[] {Reason.REPLACEMENT, Reason.LEGACY_OR_MIXED, Reason.INLINE}) {
      assertFalse(select(selector, exclusion, ONE_MIB, 4));
      assertEquals(exclusion, selector.snapshot(4 * TICK).lastReason());
    }
    assertFalse(selector.prePersist(null, SMALL_PACK, true, null));
    assertEquals(Reason.NESTED_TRANSACTION, selector.snapshot(4 * TICK).lastReason());

    for (int index = 4; index <= 10; index++) {
      sample(selector, index, false, 0);
      assertTrue(select(selector, null, SMALL_PACK, index + 1));
    }
    for (int index = 11; index <= 16; index++) {
      sample(selector, index, false, 0);
    }
    assertFalse(select(selector, null, SMALL_PACK, 17));
    assertEquals(ONE_MIB, selector.snapshot(17 * TICK).effectiveThresholdBytes());
  }

  @Test
  void highRoundTripWithoutLocalCompetitionCannotPromoteIndependentWrites() {
    PackPublicationSelector selector = selector(UUID.randomUUID().toString(), "independent");
    for (int index = 1; index <= 12; index++) {
      sample(selector, index, false, LONG_LOCK_WAIT);
    }
    assertFalse(select(selector, null, SMALL_PACK, 13));
    assertTrue(select(selector, null, ONE_MIB, 13));
    assertEquals(12, selector.snapshot(13 * TICK).samples());
    assertEquals(LONG_LOCK_WAIT, selector.snapshot(13 * TICK).lockAcquisitionEwmaNanos());
    assertEquals(0.0, selector.snapshot(13 * TICK).queuedWritersEwma());
  }

  @Test
  void sharesTheSameRepositoryWithoutInheritingACollidingRepositorysState() {
    String database = UUID.randomUUID().toString();
    PackPublicationSelector original = selector(database, "Aa");
    PackPublicationSelector same = selector(database, "Aa");
    PackPublicationSelector colliding = selector(database, "BB");
    for (int index = 1; index <= 3; index++) {
      sample(original, index, true, LONG_LOCK_WAIT);
    }
    assertTrue(select(same, null, SMALL_PACK, 4));

    try (var active = original.begin(SMALL_PACK, 4 * TICK)) {
      assertNull(colliding.begin(SMALL_PACK, 4 * TICK));
      assertFalse(colliding.prePersist(null, SMALL_PACK, false, null));
      assertEquals(Reason.STRIPE_BUSY, colliding.snapshot(4 * TICK).lastReason());
      assertEquals(0, colliding.snapshot(4 * TICK).samples());
    }
    assertFalse(select(colliding, null, SMALL_PACK, 5));
    assertFalse(select(original, null, SMALL_PACK, 6));
  }

  @Test
  void poolOverridesCannotShareContentionAcrossDifferentSchemasOrEndpoints() {
    String jdbcUrl = "jdbc:postgresql://example.test/" + UUID.randomUUID();
    Map<String, String> first =
        Map.of("hibernate.connection.url", jdbcUrl, "hibernate.hikari.schema", "one");
    Map<String, String> otherSchema =
        Map.of("hibernate.connection.url", jdbcUrl, "hibernate.hikari.schema", "two");
    Map<String, String> otherUrl =
        Map.of(
            "hibernate.connection.url",
            jdbcUrl,
            "hibernate.hikari.schema",
            "one",
            "hibernate.hikari.jdbcUrl",
            "jdbc:postgresql://other.test/db");

    var original = selector(PackPublicationSelector.key(first, "same-name", () -> "factory-one"));
    var same = selector(PackPublicationSelector.key(first, "same-name", () -> "factory-two"));
    var independentSchema =
        selector(PackPublicationSelector.key(otherSchema, "same-name", () -> "factory-three"));
    var independentEndpoint =
        selector(PackPublicationSelector.key(otherUrl, "same-name", () -> "factory-four"));
    for (int index = 1; index <= 3; index++) {
      sample(original, index, true, LONG_LOCK_WAIT);
    }

    assertTrue(select(same, null, SMALL_PACK, 4));
    assertFalse(select(independentSchema, null, SMALL_PACK, 4));
    assertFalse(select(independentEndpoint, null, SMALL_PACK, 4));
  }

  @ParameterizedTest
  @CsvSource({
    "hibernate.hikari.connectionInitSql, SET SCHEMA ONE, SET SCHEMA TWO",
    "hibernate.hikari.dataSource.SCHEMA, ONE, TWO",
    "hibernate.connection.SCHEMA, ONE, TWO"
  })
  void connectionInitializationCannotTrainAnIndependentSchema(
      String setting, String firstSchema, String secondSchema) {
    String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID();
    Map<String, String> first =
        Map.of("hibernate.connection.url", jdbcUrl, setting, firstSchema);
    Map<String, String> second =
        Map.of("hibernate.connection.url", jdbcUrl, setting, secondSchema);
    String firstFactory = UUID.randomUUID().toString();
    String secondFactory = UUID.randomUUID().toString();
    var original = selector(PackPublicationSelector.key(first, "same-name", () -> firstFactory));
    var sameFactory =
        selector(PackPublicationSelector.key(first, "same-name", () -> firstFactory));
    var independentSchema =
        selector(PackPublicationSelector.key(second, "same-name", () -> secondFactory));
    for (int index = 1; index <= 3; index++) {
      sample(original, index, true, LONG_LOCK_WAIT);
    }

    assertTrue(select(sameFactory, null, SMALL_PACK, 4));
    assertFalse(select(independentSchema, null, SMALL_PACK, 4));
  }

  @Test
  void returnsToTheFallbackAfterAnIdleInterval() {
    PackPublicationSelector selector = selector(UUID.randomUUID().toString(), "inactive");
    for (int index = 1; index <= 3; index++) {
      sample(selector, index, true, LONG_LOCK_WAIT);
    }
    assertTrue(select(selector, null, SMALL_PACK, 4));
    assertFalse(select(selector, null, SMALL_PACK, 1200));
    assertEquals(0, selector.snapshot(1200 * TICK).samples());
  }

  private static PackPublicationSelector selector(String database, String repository) {
    return selector(new PackPublicationSelector.Key(Map.of("database", database), repository));
  }

  private static PackPublicationSelector selector(PackPublicationSelector.Key key) {
    return new PackPublicationSelector("contention-aware", true, ONE_MIB, key);
  }

  private static boolean select(
      PackPublicationSelector selector, Reason exclusion, long payload, int step) {
    try (var sample = exclusion == null ? selector.begin(payload, step * TICK) : null) {
      return selector.prePersist(exclusion, payload, false, sample);
    }
  }

  private static void sample(
      PackPublicationSelector selector, int step, boolean queued, long lockWaitNanos) {
    long now = step * TICK;
    try (var sample = selector.begin(SMALL_PACK, now);
        var other = queued ? selector.begin(SMALL_PACK, now) : null) {
      if (other != null) {
        other.beforeLock();
      }
      sample.beforeLock();
      sample.lockAcquired(lockWaitNanos);
      sample.afterLock();
      if (other != null) {
        other.afterLock();
      }
      sample.committed(now + PUBLICATION_DURATION);
    }
  }
}
