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
 * changed. When {@link #text()} is present, path text is interpreted by the full-text analyzer;
 * otherwise path matching retains its literal, case-insensitive fragment semantics.
 *
 * <p>An object-id restriction is distinct from no restriction. An explicitly empty candidate set
 * matches no commits, while omitting the restriction searches every indexed commit in the logical
 * repository.
 */
public final class CommitHistoryQuery {

  private static final int DEFAULT_LIMIT = 100;

  private final String repositoryName;
  private final String text;
  private final String authorEmail;
  private final String pathFragment;
  private final Instant from;
  private final Instant to;
  private final boolean objectIdRestriction;
  private final List<String> objectIds;
  private final int limit;

  private CommitHistoryQuery(Builder builder) {
    repositoryName = requireText(builder.repositoryName, "repositoryName");
    text = normalize(builder.text);
    authorEmail = normalize(builder.authorEmail);
    pathFragment = normalize(builder.pathFragment);
    from = builder.from;
    to = builder.to;
    objectIdRestriction = builder.objectIds != null;
    objectIds = builder.objectIds == null ? List.of() : builder.objectIds;
    limit = builder.limit;

    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
  }

  /**
   * Start a query for one logical repository.
   *
   * @param repositoryName logical repository name
   * @return query builder
   */
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

  public String pathFragment() {
    return pathFragment;
  }

  public Instant from() {
    return from;
  }

  public Instant to() {
    return to;
  }

  public boolean hasObjectIdRestriction() {
    return objectIdRestriction;
  }

  public List<String> objectIds() {
    return objectIds;
  }

  public int limit() {
    return limit;
  }

  /** Builder for {@link CommitHistoryQuery}. */
  public static final class Builder {

    private final String repositoryName;
    private String text;
    private String authorEmail;
    private String pathFragment;
    private Instant from;
    private Instant to;
    private List<String> objectIds;
    private int limit = DEFAULT_LIMIT;

    private Builder(String repositoryName) {
      this.repositoryName = requireText(repositoryName, "repositoryName");
    }

    /**
     * Restrict results using a simple-query-string expression over messages, paths and changed text.
     *
     * @param text full-text expression, or {@code null} to omit the predicate
     * @return this builder
     */
    public Builder matchingText(String text) {
      this.text = text;
      return this;
    }

    /**
     * Restrict results to commits authored with the exact email address.
     *
     * @param authorEmail author email, or {@code null} to omit the predicate
     * @return this builder
     */
    public Builder authoredBy(String authorEmail) {
      this.authorEmail = authorEmail;
      return this;
    }

    /**
     * Restrict results to commits that changed a path containing the fragment.
     *
     * @param pathFragment path fragment, or {@code null} to omit the predicate
     * @return this builder
     */
    public Builder touchingPath(String pathFragment) {
      this.pathFragment = pathFragment;
      return this;
    }

    /**
     * Restrict results to an exact set of commit object IDs.
     *
     * <p>The supplied IDs are trimmed, deduplicated and defensively copied. An empty collection is a
     * deliberate match-none restriction; omit this method to search every commit in the repository.
     *
     * @param objectIds exact candidate commit IDs
     * @return this builder
     */
    public Builder restrictedToObjectIds(Collection<String> objectIds) {
      Objects.requireNonNull(objectIds, "objectIds");
      LinkedHashSet<String> normalized = new LinkedHashSet<>();
      for (String objectId : objectIds) {
        normalized.add(requireText(objectId, "objectId"));
      }
      this.objectIds = List.copyOf(normalized);
      return this;
    }

    /**
     * Restrict results to commits at or after the inclusive instant.
     *
     * @param from inclusive lower bound
     * @return this builder
     */
    public Builder from(Instant from) {
      this.from = from;
      return this;
    }

    /**
     * Restrict results to commits at or before the inclusive instant.
     *
     * @param to inclusive upper bound
     * @return this builder
     */
    public Builder to(Instant to) {
      this.to = to;
      return this;
    }

    /**
     * Restrict results to an inclusive time interval.
     *
     * @param from inclusive lower bound
     * @param to inclusive upper bound
     * @return this builder
     */
    public Builder between(Instant from, Instant to) {
      this.from = Objects.requireNonNull(from, "from");
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /**
     * Set the maximum number of results.
     *
     * @param limit non-negative result limit
     * @return this builder
     */
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
