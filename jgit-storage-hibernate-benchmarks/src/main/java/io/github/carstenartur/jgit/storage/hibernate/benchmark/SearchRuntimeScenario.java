/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * One deliberately bounded Hibernate Search/Lucene runtime tuning scenario.
 *
 * <p>The scenario IDs form the retained benchmark contract. They avoid a blind Cartesian product:
 * synchronization/refresh, writer-memory/threading and projection transaction size are measured in
 * separate controlled families so one result can be attributed to the setting being changed.
 */
record SearchRuntimeScenario(
    String id,
    String synchronization,
    int refreshIntervalMs,
    Integer writerRamBufferMb,
    Integer backendThreads,
    int projectionBatchSize) {

  static final String SYNCHRONIZATION_PROPERTY =
      "hibernate.search.indexing.plan.synchronization.strategy";
  static final String REFRESH_INTERVAL_PROPERTY =
      "hibernate.search.backend.io.refresh_interval";
  static final String WRITER_RAM_BUFFER_PROPERTY =
      "hibernate.search.backend.io.writer.ram_buffer_size";
  static final String BACKEND_THREADS_PROPERTY =
      "hibernate.search.backend.thread_pool.size";

  private static final Set<String> SYNCHRONIZATION_STRATEGIES =
      Set.of("async", "write-sync", "read-sync", "sync");
  private static final Set<Integer> REFRESH_INTERVALS = Set.of(0, 100, 500, 1_000);
  private static final Set<Integer> WRITER_RAM_BUFFERS = Set.of(16, 32, 64, 128, 256);
  private static final Set<Integer> BACKEND_THREAD_COUNTS = Set.of(1, 2, 4, 8);
  private static final Set<Integer> BATCH_SIZES = Set.of(1, 50, 250, 500);

  SearchRuntimeScenario {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(synchronization, "synchronization");
    if (!SYNCHRONIZATION_STRATEGIES.contains(synchronization)) {
      throw new IllegalArgumentException("Unsupported synchronization strategy " + synchronization);
    }
    if (!REFRESH_INTERVALS.contains(refreshIntervalMs)) {
      throw new IllegalArgumentException("Unsupported refresh interval " + refreshIntervalMs);
    }
    if (writerRamBufferMb != null && !WRITER_RAM_BUFFERS.contains(writerRamBufferMb)) {
      throw new IllegalArgumentException("Unsupported writer RAM buffer " + writerRamBufferMb);
    }
    if (backendThreads != null && !BACKEND_THREAD_COUNTS.contains(backendThreads)) {
      throw new IllegalArgumentException("Unsupported backend thread count " + backendThreads);
    }
    if (!BATCH_SIZES.contains(projectionBatchSize)) {
      throw new IllegalArgumentException("Unsupported projection batch size " + projectionBatchSize);
    }
  }

  static SearchRuntimeScenario fromId(String id) {
    Objects.requireNonNull(id, "id");
    String normalized = id.trim().toLowerCase(Locale.ROOT);
    if ("reference".equals(normalized)) {
      return new SearchRuntimeScenario("reference", "write-sync", 0, null, null, 50);
    }
    if (normalized.startsWith("sync-")) {
      int refreshSeparator = normalized.lastIndexOf("-r");
      if (refreshSeparator <= "sync-".length()) {
        throw new IllegalArgumentException("Invalid synchronization scenario " + id);
      }
      String synchronization = normalized.substring("sync-".length(), refreshSeparator);
      int refresh = Integer.parseInt(normalized.substring(refreshSeparator + 2));
      return new SearchRuntimeScenario(normalized, synchronization, refresh, null, null, 50);
    }
    if (normalized.startsWith("writer-")) {
      String[] parts = normalized.split("-");
      if (parts.length != 3
          || !parts[1].startsWith("ram")
          || !parts[2].startsWith("t")) {
        throw new IllegalArgumentException("Invalid writer scenario " + id);
      }
      int ram = Integer.parseInt(parts[1].substring(3));
      int threads = Integer.parseInt(parts[2].substring(1));
      return new SearchRuntimeScenario(normalized, "write-sync", 0, ram, threads, 50);
    }
    if (normalized.startsWith("batch-")) {
      int batch = Integer.parseInt(normalized.substring("batch-".length()));
      return new SearchRuntimeScenario(normalized, "write-sync", 0, null, null, batch);
    }
    throw new IllegalArgumentException("Unknown Search runtime scenario " + id);
  }

  /** Fast PR/push evidence spanning every tuning family. */
  static List<String> smokeScenarioIds() {
    return List.of(
        "reference",
        "sync-async-r500",
        "sync-read-sync-r0",
        "sync-sync-r0",
        "sync-write-sync-r500",
        "writer-ram64-t4",
        "batch-250");
  }

  /** Scheduled/manual matrix requested by the runtime-tuning performance issue. */
  static List<String> fullScenarioIds() {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    ids.add("reference");
    for (String synchronization : List.of("async", "write-sync", "read-sync", "sync")) {
      for (int refresh : List.of(0, 100, 500, 1_000)) {
        ids.add("sync-" + synchronization + "-r" + refresh);
      }
    }
    for (int ram : List.of(16, 32, 64, 128, 256)) {
      for (int threads : List.of(1, 2, 4, 8)) {
        ids.add("writer-ram" + ram + "-t" + threads);
      }
    }
    for (int batch : List.of(1, 50, 250, 500)) {
      ids.add("batch-" + batch);
    }
    return List.copyOf(new ArrayList<>(ids));
  }

  void apply(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    properties.put(SYNCHRONIZATION_PROPERTY, synchronization);
    properties.put(REFRESH_INTERVAL_PROPERTY, Integer.toString(refreshIntervalMs));
    if (writerRamBufferMb != null) {
      properties.put(WRITER_RAM_BUFFER_PROPERTY, Integer.toString(writerRamBufferMb));
    }
    if (backendThreads != null) {
      properties.put(BACKEND_THREADS_PROPERTY, Integer.toString(backendThreads));
    }
    properties.put(
        CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, Integer.toString(projectionBatchSize));
    properties.put("hibernate.jdbc.batch_size", Integer.toString(projectionBatchSize));
  }
}
