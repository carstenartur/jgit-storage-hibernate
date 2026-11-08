/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Streaming, size-limited raw records. The trace pass intentionally includes logging overhead. */
final class SearchRuntimeDiagnosticLog implements AutoCloseable {
  private static final int MAX_RECORDS = 200_000;
  private static final long MAX_UTF8_BYTES = 64L * 1024 * 1024;
  private final long origin = System.nanoTime();
  private final List<Csv> files = new ArrayList<>();
  private final AtomicReference<Throwable> traceFailure = new AtomicReference<>();
  private final Csv trace;
  final Csv samples;
  final Csv transactions;
  final Csv queries;
  private final Logger logger = Logger.getLogger("org.hibernate.search.lucene.infostream");
  private final Level previousLevel;
  private final boolean previousParents;
  private final Handler handler;
  private volatile String phase = "setup";

  SearchRuntimeDiagnosticLog(Path output, boolean traced) throws IOException {
    try {
      trace = csv(output, "trace.csv", "seq,elapsed_ns,phase,thread,component,message");
      samples =
          csv(
              output,
              "samples.csv",
              "seq,elapsed_ns,phase,submitted_commits,acknowledged_commits,"
                  + "last_visible_commits,visibility_sample_age_ns,queries_completed,heap_used_bytes,"
                  + "gc_count,gc_time_ms,active_workers,gate_entered,producer_wait");
      transactions =
          csv(
              output,
              "transactions.csv",
              "seq,phase,started_ns,completed_ns,first_index,"
                  + "last_index,commits,deadline_lateness_ns");
      queries =
          csv(
              output,
              "queries.csv",
              "seq,phase,started_ns,completed_ns,stable_hits,"
                  + "visible_commits,visibility_observed_ns");
    } catch (IOException failure) {
      for (Csv file : files) {
        try {
          file.close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
      }
      throw failure;
    }
    previousLevel = logger.getLevel();
    previousParents = logger.getUseParentHandlers();
    handler =
        traced
            ? new Handler() {
              @Override
              public void publish(LogRecord record) {
                if (!isLoggable(record)) return;
                try {
                  // JBoss Logging's JDK provider formats logf before publishing the JUL record.
                  String formatted = record.getMessage();
                  String prefix = "HSEARCH000226: ";
                  int separator = formatted.indexOf(": ", prefix.length());
                  if (!formatted.startsWith(prefix) || separator < 0)
                    throw new IOException("Unexpected Lucene InfoStream record format");
                  recordTrace(
                      formatted.substring(prefix.length(), separator),
                      formatted.substring(separator + 2));
                } catch (Throwable failure) {
                  traceFailure.compareAndSet(null, failure);
                }
              }

              @Override
              public void flush() {}

              @Override
              public void close() {}
            }
            : null;
    if (handler != null) {
      handler.setLevel(Level.ALL);
      logger.addHandler(handler);
      logger.setUseParentHandlers(false);
      logger.setLevel(Level.FINEST);
    }
    phase("setup");
  }

  long elapsed() {
    return System.nanoTime() - origin;
  }

  long timestamp(long absoluteNanos) {
    return absoluteNanos - origin;
  }

  String phase() {
    return phase;
  }

  synchronized void phase(String value) throws IOException {
    phase = value;
    recordTrace("DIAGNOSTIC", "phase " + value);
  }

  private synchronized void recordTrace(String component, String message) throws IOException {
    trace.row(elapsed(), phase, Thread.currentThread().getName(), component, message);
  }

  void assertHealthy() {
    if (traceFailure.get() != null)
      throw new IllegalStateException(
          "Could not retain complete Lucene activity trace", traceFailure.get());
  }

  long traceRecords() {
    return trace.records;
  }

  private Csv csv(Path root, String name, String header) throws IOException {
    Csv file = new Csv(root.resolve(name), header);
    files.add(file);
    return file;
  }

  @Override
  public void close() throws IOException {
    if (handler != null) {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
      logger.setUseParentHandlers(previousParents);
    }
    IOException failure = null;
    for (Csv file : files) {
      try {
        file.close();
      } catch (IOException close) {
        if (failure == null) failure = close;
        else failure.addSuppressed(close);
      }
    }
    if (failure != null) throw failure;
    assertHealthy();
  }

  static final class Csv implements AutoCloseable {
    private final BufferedWriter writer;
    private long records;
    private long estimatedBytes;

    Csv(Path path, String header) throws IOException {
      writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
      writer.write(header);
      writer.newLine();
    }

    synchronized void row(Object... values) throws IOException {
      if (records >= MAX_RECORDS) throw new IOException("Diagnostic record limit exceeded");
      StringBuilder line = new StringBuilder().append(records + 1);
      for (Object value : values) {
        String text = String.valueOf(value);
        if (text.length() > 16_384) throw new IOException("Diagnostic field limit exceeded");
        line.append(',').append('"').append(text.replace("\"", "\"\"")).append('"');
      }
      estimatedBytes += 3L * line.length() + 1;
      if (estimatedBytes > MAX_UTF8_BYTES) throw new IOException("Diagnostic file limit exceeded");
      writer.write(line.toString());
      writer.newLine();
      records++;
    }

    @Override
    public synchronized void close() throws IOException {
      writer.close();
    }
  }
}
