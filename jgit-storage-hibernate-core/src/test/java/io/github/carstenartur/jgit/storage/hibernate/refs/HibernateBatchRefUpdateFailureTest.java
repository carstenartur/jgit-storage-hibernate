/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;

class HibernateBatchRefUpdateFailureTest {

  private static final ObjectId OBJECT_ID =
      ObjectId.fromString("1111111111111111111111111111111111111111");

  @Test
  void transactionRollbackReplacesAlreadySuccessfulCommandResults() {
    ReceiveCommand alreadyApplied =
        new ReceiveCommand(ObjectId.zeroId(), OBJECT_ID, "refs/heads/already-applied");
    alreadyApplied.setResult(ReceiveCommand.Result.OK);
    ReceiveCommand notAttempted =
        new ReceiveCommand(ObjectId.zeroId(), OBJECT_ID, "refs/heads/not-attempted");

    HibernateBatchRefUpdate.rejectAll(
        List.of(alreadyApplied, notAttempted), "repository transaction failed");

    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, alreadyApplied.getResult());
    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, notAttempted.getResult());
    assertEquals("repository transaction failed", alreadyApplied.getMessage());
    assertEquals("repository transaction failed", notAttempted.getMessage());
  }

  @Test
  void transactionFailureMessageKeepsBoundedNormalizedDiagnostics() {
    String longDetail = "first\nsecond " + "x".repeat(300);

    String message =
        HibernateBatchRefUpdate.transactionFailureMessage(new IOException(longDetail));

    assertTrue(message.startsWith("repository transaction failed (IOException): first second "));
    assertTrue(message.endsWith("…"));
    assertTrue(message.length() < 330);
  }

  @Test
  void transactionFailureMessageStillIdentifiesAnExceptionWithoutDetail() {
    assertEquals(
        "repository transaction failed (IOException)",
        HibernateBatchRefUpdate.transactionFailureMessage(new IOException()));
  }
}
