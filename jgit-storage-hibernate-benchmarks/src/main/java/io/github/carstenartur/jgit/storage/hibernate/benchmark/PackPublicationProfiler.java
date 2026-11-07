/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.results.SampleTimeResult;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.util.SampleBuffer;

/** Retains pack publication timings and observed JVM heap occupancy in JMH results. */
public final class PackPublicationProfiler implements InternalProfiler {

  private static volatile WorkerSamples[] currentIteration;

  @Override
  public String getDescription() {
    return "Pack/ref operation duration, pack repository-lock acquisition and observed heap";
  }

  @Override
  public void beforeIteration(BenchmarkParams benchmark, IterationParams iteration) {
    WorkerSamples[] workers = new WorkerSamples[benchmark.getThreads()];
    Arrays.setAll(workers, ignored -> new WorkerSamples());
    currentIteration = workers;
  }

  static long start() {
    return currentIteration != null ? System.nanoTime() : 0;
  }

  static void record(int threadIndex, long durationNanos, long acquisitionNanos) {
    WorkerSamples[] workers = currentIteration;
    if (workers != null) {
      WorkerSamples sample = workers[threadIndex];
      sample.duration.add(durationNanos);
      if (acquisitionNanos >= 0) {
        sample.lock.add(acquisitionNanos);
      }
      Runtime runtime = Runtime.getRuntime();
      sample.peakHeapBytes =
          Math.max(sample.peakHeapBytes, runtime.totalMemory() - runtime.freeMemory());
    }
  }

  @Override
  public Collection<? extends Result<?>> afterIteration(
      BenchmarkParams benchmark, IterationParams iteration, IterationResult result) {
    WorkerSamples[] workers = currentIteration;
    currentIteration = null;
    SampleBuffer duration = new SampleBuffer();
    SampleBuffer lock = new SampleBuffer();
    long peakHeapBytes = 0;
    for (WorkerSamples worker : workers) {
      duration.addAll(worker.duration);
      lock.addAll(worker.lock);
      peakHeapBytes = Math.max(peakHeapBytes, worker.peakHeapBytes);
    }
    SampleTimeResult operation =
        new SampleTimeResult(
            ResultRole.SECONDARY, "packAndRefPublicationDuration", duration, TimeUnit.MICROSECONDS);
    ScalarResult heap =
        new ScalarResult(
            "publicationObservedHeapPeak", peakHeapBytes, "bytes", AggregationPolicy.MAX);
    if (lock.count() == 0) {
      return List.of(operation, heap);
    }
    return List.of(
        operation,
        heap,
        new SampleTimeResult(
            ResultRole.SECONDARY, "packPublicationLockAcquisition", lock, TimeUnit.MICROSECONDS));
  }

  private static final class WorkerSamples {
    private final SampleBuffer duration = new SampleBuffer();
    private final SampleBuffer lock = new SampleBuffer();
    private long peakHeapBytes;
  }
}
