/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import java.util.Set;

/** Raised when one repository's persisted Search projection uses another semantic profile. */
public final class SearchIndexProfileMismatchException extends IllegalStateException {

  private final String repositoryName;
  private final String configuredProfile;
  private final Set<String> persistedProfiles;

  SearchIndexProfileMismatchException(
      String repositoryName, String configuredProfile, Set<String> persistedProfiles) {
    super(
        "Search projection profile mismatch for repository '"
            + repositoryName
            + "': configured="
            + configuredProfile
            + ", persisted="
            + persistedProfiles
            + ". Rebuild this repository with CommitProjectionRebuilder before serving or incrementally indexing it.");
    this.repositoryName = repositoryName;
    this.configuredProfile = configuredProfile;
    this.persistedProfiles = Set.copyOf(persistedProfiles);
  }

  public String repositoryName() {
    return repositoryName;
  }

  public String configuredProfile() {
    return configuredProfile;
  }

  public Set<String> persistedProfiles() {
    return persistedProfiles;
  }
}
