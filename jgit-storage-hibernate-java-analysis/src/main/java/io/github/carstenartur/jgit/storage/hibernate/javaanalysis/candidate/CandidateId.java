/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Stable identifier for a semantic mining candidate. */
public record CandidateId(
    String repositoryName,
    String sourceCommitId,
    String category,
    String analyzerName,
    String semanticPayload,
    String id) {

  public CandidateId {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(sourceCommitId, "sourceCommitId");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(analyzerName, "analyzerName");
    Objects.requireNonNull(semanticPayload, "semanticPayload");
    Objects.requireNonNull(id, "id");
    if (id.length() != 64) {
      throw new IllegalArgumentException("id must be a 64-character hex SHA-256 string");
    }
  }

  public static CandidateId of(
      String repositoryName,
      String sourceCommitId,
      String category,
      String analyzerName,
      String semanticPayload) {
    String payload = String.join("\n",
        repositoryName,
        sourceCommitId,
        category,
        analyzerName,
        semanticPayload);
    return new CandidateId(
        repositoryName,
        sourceCommitId,
        category,
        analyzerName,
        semanticPayload,
        sha256(payload));
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
