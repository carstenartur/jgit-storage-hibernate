/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable compound query for the generic commit-history projection.
 *
 * <p>The optional predicates are combined with logical {@code AND}. Path matching applies to paths
 * changed by the commit relative to its first parent; every path in a root commit is considered
 * changed. {@link PathMatch#LITERAL_FRAGMENT} preserves the original case-insensitive SQL fragment
 * behavior for path-only structured queries. {@link PathMatch#ANALYZED_TERMS} and {@link
 * PathMatch#EXACT} explicitly select the Lucene path fields and therefore avoid a leading-wildcard
 * relational query even when no free-text expression is present.
 *
 * <p>Time predicates and newest-first ordering use committer time by default. Call {@link
 * Builder#usingAuthorTime()} when the question concerns when the original author wrote the change
 * rather than when the commit entered the current history.
 *
 * <p>An object-id restriction is distinct from no restriction. An explicitly empty candidate set
 * matches no commits, while omitting the restriction searches every indexed commit in the logical
 * repository.
 */
public final class CommitHistoryQuery {

  /** Timestamp dimension used for range predicates and chronological ordering. */
  public enum TimestampField {
    AUTHOR,
    COMMITTER
  }

  /** Path predicate semantics selected by the caller. */
  public enum PathMatch {
    /** Literal, case-insensitive path substring through the relational compatibility query. */
    LITERAL_FRAGMENT,
    /** Lowercased path components/terms through the analyzed Lucene path field. */
    ANALYZED_TERMS,
    /** One complete changed path through the exact multivalued Lucene keyword field. */
    EXACT
  }

  private static final int DEFAULT_LIMIT = 100;

  private final String repositoryName;
  private final String text;
  private final String authorEmail;
  private final String committerEmail;
  private final String pathFragment;
  private final PathMatch pathMatch;
  private final Instant from;
  private final Instant to;
  private final TimestampField timestampField;
  private final boolean objectIdRestriction;
  private final List<String> objectIds;
  private final int offset;
  private final int limit;

  private CommitHistoryQuery(Builder builder) {
    repositoryName = requireText(builder.repositoryName, "repositoryName");
    text = normalize(builder.text);
    authorEmail = normalize(builder.authorEmail);
    committerEmail = normalize(builder.committerEmail);
    pathFragment = normalize(builder.pathFragment);
    pathMatch = Objects.requireNonNull(builder.pathMatch, "pathMatch");
    from = builder.from;
    to = builder.to;
    timestampField = Objects.requireNonNull(builder.timestampField, "timestampField");
    objectIdRestriction = builder.objectIds != null;
    objectIds = builder.objectIds == null ? List.of() : builder.objectIds;
    offset = builder.offset;
    limit = builder.limit;

    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
  }

  /** Start a query for one logical repository. */
  public static Builder forRepository(String repositoryName) {
    return new Builder(repositoryName);
  }

  public String repositoryName() {
    return repositoryName;
  }

  public String text() {
    return text;
  }

  public String authorEmail() {
    return authorEmail;
  }

  public String committerEmail() {
    return committerEmail;
  }

  public String pathFragment() {
    return pathFragment;
  }

  public PathMatch pathMatch() {
    return pathMatch;
  }

  public Instant from() {
    return from;
  }

  public Instant to() {
    return to;
  }

  public TimestampField timestampField() {
    return timestampField;
  }

  public boolean hasObjectIdRestriction() {
    return objectIdRestriction;
  }

  public List<String> objectIds() {
    return objectIds;
  }

  public int offset() {
    return offset;
  }

  public int limit() {
    return limit;
  }

  /** Whether this query explicitly requires an indexed Search path. */
  boolean requiresSearchBackend() {
    return text != null || (pathFragment != null && pathMatch != PathMatch.LITERAL_FRAGMENT);
  }

  /** Builder for {@link CommitHistoryQuery}. */
  public static final class Builder {

    private final String repositoryName;
    private String text;
    private String authorEmail;
    private String committerEmail;
    private String pathFragment;
    private PathMatch pathMatch = PathMatch.LITERAL_FRAGMENT;
    private Instant from;
    private Instant to;
    private TimestampField timestampField = TimestampField.COMMITTER;
    private List<String> objectIds;
    private int offset;
    private int limit = DEFAULT_LIMIT;

    private Builder(String repositoryName) {
      this.repositoryName = requireText(repositoryName, "repositoryName");
    }

    public Builder matchingText(String text) {
      this.text = text;
      return this;
    }

    public Builder authoredBy(String authorEmail) {
      this.authorEmail = authorEmail;
      return this;
    }

    /** Restrict results to commits recorded by this committer identity. */
    public Builder committedBy(String committerEmail) {
      this.committerEmail = committerEmail;
      return this;
    }

    /**
     * Match a literal, case-insensitive path substring.
     *
     * <p>For a path-only query this retains the original relational compatibility semantics. When a
     * free-text expression is also present the full-text query already runs through Lucene and this
     * path value is interpreted as analyzed terms, preserving the pre-existing compound-query
     * behavior.
     */
    public Builder touchingPath(String pathFragment) {
      this.pathFragment = pathFragment;
      pathMatch = PathMatch.LITERAL_FRAGMENT;
      return this;
    }

    /** Match all analyzed path components/terms through Lucene. */
    public Builder touchingPathTerms(String pathTerms) {
      pathFragment = pathTerms;
      pathMatch = PathMatch.ANALYZED_TERMS;
      return this;
    }

    /** Match one complete changed path through the exact Lucene keyword field. */
    public Builder touchingExactPath(String path) {
      pathFragment = path;
      pathMatch = PathMatch.EXACT;
      return this;
    }

    public Builder restrictedToObjectIds(Collection<String> objectIds) {
      Objects.requireNonNull(objectIds, "objectIds");
      LinkedHashSet<String> normalized = new LinkedHashSet<>();
      for (String objectId : objectIds) {
        normalized.add(requireText(objectId, "objectId"));
      }
      this.objectIds = List.copyOf(normalized);
      return this;
    }

    /** Use author time for range predicates and result ordering. */
    public Builder usingAuthorTime() {
      timestampField = TimestampField.AUTHOR;
      return this;
    }

    /** Use committer time for range predicates and result ordering. This is the default. */
    public Builder usingCommitterTime() {
      timestampField = TimestampField.COMMITTER;
      return this;
    }

    public Builder from(Instant from) {
      this.from = from;
      return this;
    }

    public Builder to(Instant to) {
      this.to = to;
      return this;
    }

    /** Restrict the selected timestamp dimension to an inclusive interval. */
    public Builder between(Instant from, Instant to) {
      this.from = Objects.requireNonNull(from, "from");
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /** Restrict author time to an inclusive interval. */
    public Builder authoredBetween(Instant from, Instant to) {
      return usingAuthorTime().between(from, to);
    }

    /** Restrict committer time to an inclusive interval. */
    public Builder committedBetween(Instant from, Instant to) {
      return usingCommitterTime().between(from, to);
    }

    /** Skip the first {@code offset} matching results after deterministic ordering. */
    public Builder offset(int offset) {
      this.offset = offset;
      return this;
    }

    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    public CommitHistoryQuery build() {
      return new CommitHistoryQuery(this);
    }
  }

  private static String requireText(String value, String name) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }
}
