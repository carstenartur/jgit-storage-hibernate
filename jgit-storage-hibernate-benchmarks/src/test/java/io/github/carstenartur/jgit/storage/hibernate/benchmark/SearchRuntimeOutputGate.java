/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One-shot, explicitly armed fixture gate; never a production directory implementation. */
final class SearchRuntimeOutputGate {
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final Duration timeout;
  private volatile boolean armed;
  private volatile long enteredAt;
  private volatile long releasedAt;

  SearchRuntimeOutputGate(Duration timeout) {
    this.timeout = timeout;
  }

  void arm() {
    armed = true;
  }

  boolean entered() {
    return entered.getCount() == 0;
  }

  boolean released() {
    return release.getCount() == 0;
  }

  long enteredAt() {
    return enteredAt;
  }

  long releasedAt() {
    return releasedAt;
  }

  synchronized void release() {
    if (release.getCount() != 0) {
      releasedAt = System.nanoTime();
      release.countDown();
    }
  }

  boolean await(long millis) throws InterruptedException {
    return entered.await(millis, TimeUnit.MILLISECONDS);
  }

  void beforeOutput() throws IOException {
    if (!armed) return;
    synchronized (this) {
      if (entered.getCount() != 0) {
        enteredAt = System.nanoTime();
        entered.countDown();
      }
    }
    try {
      if (!release.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
        throw new IOException("Timed out at diagnostic output gate");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted at diagnostic output gate", interrupted);
    }
  }
}
