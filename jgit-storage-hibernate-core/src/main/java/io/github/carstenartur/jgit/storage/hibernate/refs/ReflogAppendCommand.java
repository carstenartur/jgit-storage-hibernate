/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;

/**
 * Immutable, replay-safe command for one append-only queryable reflog projection.
 *
 * <p>{@code deliveryId} is supplied by the durable source in front of the in-memory write queue. A
 * caller must reuse the same identifier and payload when retrying after an unknown commit outcome.
 * The storage-specific batch processor either returns {@code ALREADY_APPLIED} for that exact replay
 * or rejects a conflicting reuse before persisting any command in the batch.
 */
public record ReflogAppendCommand(
    String deliveryId,
    String refName,
    ObjectId oldId,
    ObjectId newId,
    String whoName,
    String whoEmail,
    Instant when,
    String message) {

  private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9._:-]+");
  private static final int MAX_REF_NAME_LENGTH = 1024;
  private static final int MAX_IDENTITY_FIELD_LENGTH = 255;

  /** Validate and normalize one immutable command. */
  public ReflogAppendCommand {
    deliveryId = requireText(deliveryId, "deliveryId");
    if (deliveryId.length() > GitReflogEntity.MAX_DELIVERY_ID_LENGTH) {
      throw new IllegalArgumentException(
          "deliveryId exceeds " + GitReflogEntity.MAX_DELIVERY_ID_LENGTH + " characters");
    }
    if (!DELIVERY_ID.matcher(deliveryId).matches()) {
      throw new IllegalArgumentException(
          "deliveryId may contain only letters, digits, '.', '_', ':' and '-'");
    }

    refName = requireText(refName, "refName");
    if (refName.length() > MAX_REF_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "refName exceeds " + MAX_REF_NAME_LENGTH + " characters");
    }
    if (!refName.startsWith("refs/")) {
      throw new IllegalArgumentException("refName must be a complete refs/... name");
    }

    oldId = Objects.requireNonNull(oldId, "oldId");
    newId = Objects.requireNonNull(newId, "newId");
    whoName = normalizeIdentityField(whoName, "whoName");
    whoEmail = normalizeIdentityField(whoEmail, "whoEmail");
    // Millisecond precision round-trips identically through every supported database timestamp
    // mapping. Higher precision would make an exact replay appear different after PostgreSQL or
    // SQL Server normalized the stored value.
    when = Objects.requireNonNull(when, "when").truncatedTo(ChronoUnit.MILLIS);
    if (message != null && message.length() > GitReflogEntity.MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException(
          "message exceeds " + GitReflogEntity.MAX_MESSAGE_LENGTH + " characters");
    }
  }

  /** Create a command from JGit's actor representation. */
  public static ReflogAppendCommand from(
      String deliveryId,
      String refName,
      ObjectId oldId,
      ObjectId newId,
      PersonIdent who,
      String message) {
    Objects.requireNonNull(who, "who");
    return new ReflogAppendCommand(
        deliveryId,
        refName,
        oldId,
        newId,
        who.getName(),
        who.getEmailAddress(),
        who.getWhenAsInstant(),
        message);
  }

  /**
   * Estimate bytes retained by the queue for admission accounting.
   *
   * <p>The value deliberately includes both object IDs and all UTF-8 command text. It is an upper
   * level memory/backpressure estimate, not a database row-size promise.
   */
  public long estimatedBytes() {
    long bytes = 80L;
    bytes += utf8Length(deliveryId);
    bytes += utf8Length(refName);
    bytes += utf8Length(whoName);
    bytes += utf8Length(whoEmail);
    bytes += message == null ? 0 : utf8Length(message);
    return bytes;
  }

  String oldIdName() {
    return oldId.name();
  }

  String newIdName() {
    return newId.name();
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String normalizeIdentityField(String value, String name) {
    String normalized = value == null ? "" : value;
    if (normalized.length() > MAX_IDENTITY_FIELD_LENGTH) {
      throw new IllegalArgumentException(
          name + " exceeds " + MAX_IDENTITY_FIELD_LENGTH + " characters");
    }
    return normalized;
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
