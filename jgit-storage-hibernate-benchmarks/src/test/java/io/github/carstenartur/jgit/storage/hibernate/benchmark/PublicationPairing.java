/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.util.List;

/** The preregistered stopping rule; measurement iterations never count as independent pairs. */
final class PublicationPairing {

  private PublicationPairing() {}

  static String policyAt(int pair, int position) {
    if (pair < 1 || pair > 96 || position < 0 || position > 1) {
      throw new IllegalArgumentException("Invalid paired-fork coordinate");
    }
    return (pair % 2 == 1) == (position == 0) ? "size" : "contention-aware";
  }

  static double lowerBoundPercent(List<Double> ratios) {
    int count = ratios.size();
    double critical =
        switch (count) {
          case 24 -> 2.709720420119227;
          case 96 -> 2.5461530351050503;
          default -> throw new IllegalArgumentException("Only the 24/96-pair looks are permitted");
        };
    double[] logs = new double[count];
    double total = 0;
    for (int index = 0; index < count; index++) {
      Double ratio = ratios.get(index);
      if (ratio == null || !Double.isFinite(ratio) || ratio <= 0) {
        throw new IllegalArgumentException("Paired throughput ratios must be finite and positive");
      }
      logs[index] = Math.log(ratio);
      total += logs[index];
    }
    double mean = total / count;
    double squared = 0;
    for (double value : logs) {
      double difference = value - mean;
      squared += difference * difference;
    }
    double standardError = Math.sqrt(squared / (count - 1) / count);
    return 100 * Math.expm1(mean - critical * standardError);
  }
}
