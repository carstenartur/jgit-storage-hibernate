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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StagingSpillMetricsH2Test {

  @Test
  void countsOnlyTheMemoryPrefixCopiedDuringARealSpill() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      StorageByteCounters counters = StorageByteCounters.from(provider.getSessionFactory());
      int prefixBytes = 128 * 1024;
      byte[] prefix = new byte[prefixBytes];
      byte[] suffix = new byte[PackExtensionStagingBuffer.MAX_MEMORY_BYTES];
      StagingSpillMetrics before = counters.stagingSpillSnapshot();

      try (PackExtensionStagingBuffer buffer =
          new PackExtensionStagingBuffer(counters, (payload, size, createdAt) -> payload.discard())) {
        buffer.write(prefix, 0, prefix.length);
        buffer.write(suffix, 0, suffix.length);
      }

      StagingSpillMetrics delta = counters.stagingSpillSnapshot().minus(before);
      assertEquals(1, delta.memoryToFileSpills());
      assertEquals(prefixBytes, delta.spilledPrefixBytes());
    }
  }

  @Test
  void anOversizedFirstWriteStartsFileBackedWithoutAFalseSpill() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      StorageByteCounters counters = StorageByteCounters.from(provider.getSessionFactory());
      byte[] payload = new byte[PackExtensionStagingBuffer.MAX_MEMORY_BYTES + 1];

      try (PackExtensionStagingBuffer buffer =
          new PackExtensionStagingBuffer(counters, (staged, size, createdAt) -> staged.discard())) {
        buffer.write(payload, 0, payload.length);
      }

      assertEquals(StagingSpillMetrics.ZERO, counters.stagingSpillSnapshot());
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:spill-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
