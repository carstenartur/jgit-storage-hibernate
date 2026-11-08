/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Opt-in phase checkpoints outside measurement intervals; never qualified performance evidence. */
public final class StagingPipelineDiagnostics {
  private static final String PREFIX = "jgit.storage.benchmark.staging-pipeline";

  private StagingPipelineDiagnostics() {}

  public static boolean enabled() {
    return Boolean.getBoolean(PREFIX + ".diagnostic");
  }

  public static synchronized void checkpoint(String phase) {
    if (!enabled()) {
      return;
    }
    String line =
        Instant.now()
            + "\t"
            + ProcessHandle.current().pid()
            + "\t"
            + Thread.currentThread().threadId()
            + "\t"
            + phase
            + "\n";
    try {
      Path output = Path.of(System.getProperty(PREFIX + ".output", "target/staging-pipeline"));
      Files.createDirectories(output);
      Files.writeString(
          output.resolve("phase-checkpoints.tsv"),
          line,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot retain staging diagnostic checkpoint", failure);
    }
    System.out.print("STAGING_DIAGNOSTIC\t" + line);
  }
}
