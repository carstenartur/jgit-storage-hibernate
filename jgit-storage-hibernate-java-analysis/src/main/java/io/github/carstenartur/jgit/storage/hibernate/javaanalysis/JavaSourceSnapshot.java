/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Objects;

/** Immutable Java source input taken from a Git blob or commit snapshot. */
public record JavaSourceSnapshot(
    String repositoryName, String commitId, String blobId, String path, String source) {

  public JavaSourceSnapshot {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(blobId, "blobId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(source, "source");
  }
}
