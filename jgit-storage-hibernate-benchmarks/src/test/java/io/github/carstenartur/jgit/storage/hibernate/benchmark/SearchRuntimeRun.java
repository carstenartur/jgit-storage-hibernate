/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Selects one workload and resource budget for a Search runtime benchmark run. */
record SearchRuntimeRun(int commitCount, int burstCount, boolean full, Resources resources) {
  private static final Pattern WORKLOAD = Pattern.compile("(100|1000|10000)-(50|500)");

  static SearchRuntimeRun from(Properties properties) {
    String workload = properties.getProperty("search.runtime.workload", "100-50");
    var match = WORKLOAD.matcher(workload);
    if (!match.matches()) {
      throw new IllegalArgumentException("Unsupported Search runtime workload: " + workload);
    }
    int commits = Integer.parseInt(match.group(1));
    int burst = Integer.parseInt(match.group(2));
    if (burst >= commits) {
      throw new IllegalArgumentException("Search runtime workload must leave a preindexed history");
    }

    boolean full =
        switch (properties.getProperty("search.runtime.full", "false").toLowerCase(Locale.ROOT)) {
          case "true" -> true;
          case "false" -> false;
          default -> throw new IllegalArgumentException("search.runtime.full must be true or false");
        };
    if (full && commits == 10_000) {
      throw new IllegalArgumentException("Use the smoke settings matrix for 10,000 commits");
    }
    return new SearchRuntimeRun(
        commits,
        burst,
        full,
        Resources.fromId(properties.getProperty("search.runtime.resources", "host")));
  }

  List<String> scenarios() {
    return full ? SearchRuntimeScenario.fullScenarioIds() : SearchRuntimeScenario.smokeScenarioIds();
  }

  int warmupIterations() {
    return full ? 1 : 0;
  }

  int measurementIterations() {
    return full ? 3 : 2;
  }

  enum Resources {
    HOST("host", 0, 768, 1_024),
    CONSTRAINED("constrained", 1, 256, 512),
    NORMAL("normal", 4, 768, 1_024);

    private final String id;
    private final int processors;
    private final int initialHeapMiB;
    private final int maxHeapMiB;

    Resources(String id, int processors, int initialHeapMiB, int maxHeapMiB) {
      this.id = id;
      this.processors = processors;
      this.initialHeapMiB = initialHeapMiB;
      this.maxHeapMiB = maxHeapMiB;
    }

    private static Resources fromId(String id) {
      return switch (id) {
        case "host" -> HOST;
        case "constrained" -> CONSTRAINED;
        case "normal" -> NORMAL;
        default -> throw new IllegalArgumentException("Unsupported Search runtime resources: " + id);
      };
    }

    String id() {
      return id;
    }

    List<String> jvmArguments() {
      String initialHeap = "-Xms" + initialHeapMiB + "m";
      String maxHeap = "-Xmx" + maxHeapMiB + "m";
      return processors == 0
          ? List.of(initialHeap, maxHeap)
          : List.of(initialHeap, maxHeap, "-XX:ActiveProcessorCount=" + processors);
    }

    void verifyAffinity(String allowedCpus) {
      if (processors == 0) {
        return;
      }
      if (countCpus(allowedCpus) != processors) {
        throw new IllegalArgumentException(
            id + " Search runtime resources require a Linux CPU affinity mask with "
                + processors + " CPU(s), found " + allowedCpus + ". Launch Maven with taskset.");
      }
    }

    private static long countCpus(String allowedCpus) {
      if ("unavailable".equals(allowedCpus)) {
        return 0;
      }
      long count = 0;
      // Linux lists individual CPUs and inclusive ranges, for example 1,3-4,7.
      for (String range : allowedCpus.split(",")) {
        String[] ends = range.split("-");
        int first = Integer.parseInt(ends[0]);
        int last = ends.length == 1 ? first : Integer.parseInt(ends[1]);
        count += last - first + 1L;
      }
      return count;
    }
  }
}
