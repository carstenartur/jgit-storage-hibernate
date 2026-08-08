/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.profile;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.SessionFactory;

/** Versioned semantic profiles for the generic Git commit Search projection. */
public enum SearchIndexingProfile {
  /** Commit messages, identities and timestamps only. */
  METADATA("metadata-v1", false, ContentMode.NONE),

  /** Metadata plus analyzed and exact changed paths. */
  PATHS("paths-v1", true, ContentMode.NONE),

  /** Backward-compatible profile: paths plus bounded current changed-file blobs. */
  CONTENT("content-v1", true, ContentMode.FULL_BLOB),

  /** Experimental profile: paths plus only added/modified textual lines. */
  DIFF_HUNKS("diff-hunks-v1", true, ContentMode.DIFF_HUNKS);

  /** Hibernate property selecting the semantic indexing profile. */
  public static final String PROFILE_PROPERTY = "jgit.storage.hibernate.search.index_profile";

  /** Stable default matching the pre-profile Search semantics. */
  public static final SearchIndexingProfile DEFAULT = CONTENT;

  private final String id;
  private final boolean paths;
  private final ContentMode contentMode;

  SearchIndexingProfile(String id, boolean paths, ContentMode contentMode) {
    this.id = id;
    this.paths = paths;
    this.contentMode = contentMode;
  }

  /** Persisted and operator-visible profile identity. */
  public String id() {
    return id;
  }

  /** Whether changed paths participate in Lucene indexing. */
  public boolean indexesPaths() {
    return paths;
  }

  /** Whether changed-file text participates in Lucene indexing. */
  public boolean indexesContent() {
    return contentMode != ContentMode.NONE;
  }

  /** Content extraction strategy for this profile. */
  public ContentMode contentMode() {
    return contentMode;
  }

  /** Resolve the configured profile from a Hibernate session factory. */
  public static SearchIndexingProfile resolve(SessionFactory sessionFactory) {
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    Object configured = sessionFactory.getProperties().get(PROFILE_PROPERTY);
    if (configured == null || configured.toString().isBlank()) {
      return DEFAULT;
    }
    return parse(configured.toString());
  }

  /** Parse a configuration value by enum name or stable profile ID. */
  public static SearchIndexingProfile parse(String value) {
    Objects.requireNonNull(value, "value");
    String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    return Arrays.stream(values())
        .filter(
            profile ->
                profile.id.equals(normalized)
                    || profile.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    PROFILE_PROPERTY
                        + " must be one of "
                        + Arrays.stream(values()).map(SearchIndexingProfile::id).toList()
                        + " but was '"
                        + value
                        + "'"));
  }

  /** Resolve a persisted profile ID. */
  public static SearchIndexingProfile fromId(String id) {
    Objects.requireNonNull(id, "id");
    return Arrays.stream(values())
        .filter(profile -> profile.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown persisted Search profile '" + id + "'"));
  }

  /** Changed-file extraction mode. */
  public enum ContentMode {
    NONE,
    FULL_BLOB,
    DIFF_HUNKS
  }
}
