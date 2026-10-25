/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Ranked file identity/provenance; large analyzed fields are never returned with hits. */
public record JavaFileSearchHit(
    String repositoryName, String documentId, String commitId, String blobId, String path,
    String module, String fileType, float score) {}
