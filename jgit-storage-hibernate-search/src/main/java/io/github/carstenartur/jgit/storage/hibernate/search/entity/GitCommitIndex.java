/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.entity;

import io.github.carstenartur.jgit.storage.hibernate.search.analysis.GitTextAnalysis;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.engine.backend.analysis.AnalyzerNames;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexingDependency;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.ObjectPath;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.PropertyValue;

/** Generic searchable projection of a Git commit. */
@Entity
@Indexed
@Table(
    name = "git_commit_index",
    indexes = {
      @Index(name = "idx_commit_repo", columnList = "repository_name"),
      @Index(name = "idx_commit_repo_time", columnList = "repository_name, commit_time"),
      @Index(name = "idx_commit_repo_author_time", columnList = "repository_name, author_time"),
      @Index(name = "idx_commit_repo_author", columnList = "repository_name, author_email"),
      @Index(name = "idx_commit_repo_committer", columnList = "repository_name, committer_email")
    },
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_commit_projection_key", columnNames = "projection_key"),
      @UniqueConstraint(
          name = "uk_commit_repo_object",
          columnNames = {"repository_name", "object_id"})
    })
public class GitCommitIndex {

  /** Full-text field containing lowercase path components split at punctuation. */
  public static final String CHANGED_PATH_TERMS_FIELD = "changedPathTerms";

  /** Keyword field containing one exact value per changed path. */
  public static final String CHANGED_PATH_EXACT_FIELD = "changedPathExact";

  /**
   * Assigned ORM and Hibernate Search document identifier.
   *
   * <p>Assigning this value before persistence allows Hibernate to collect projection inserts into
   * real JDBC batches. Existing migration-backed rows receive a stable {@code legacy-<id>} value;
   * new rows use UUIDs. The historical numeric identity column remains readable for compatibility
   * but is no longer the ORM identifier.
   */
  @Id
  @Column(name = "projection_key", nullable = false, updatable = false, length = 36)
  private String projectionKey = UUID.randomUUID().toString();

  /**
   * Historical database identity retained as read-only compatibility metadata.
   *
   * <p>Migration-backed databases continue generating this column. Disposable Hibernate-created
   * schemas may leave it {@code null}; application logic must use {@link #getProjectionKey()} or the
   * repository/object-id key instead.
   */
  @Column(name = "id", insertable = false, updatable = false)
  private Long id;

  /** Stable semantic profile that produced this projection and its Lucene fields. */
  @KeywordField
  @Column(name = "index_profile", nullable = false, length = 32)
  private String indexProfile = SearchIndexingProfile.DEFAULT.id();

  @KeywordField
  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField(projectable = Projectable.YES, sortable = Sortable.YES)
  @Column(name = "object_id", nullable = false, length = 40)
  private String objectId;

  @FullTextField(
      analyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER,
      searchAnalyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER,
      projectable = Projectable.YES)
  @Nationalized
  @Column(name = "short_message", length = 2048)
  private String shortMessage;

  @FullTextField(
      analyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER,
      searchAnalyzer = GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER)
  @Nationalized
  @Column(name = "full_message", length = 8192)
  private String fullMessage;

  @KeywordField(projectable = Projectable.YES)
  @Nationalized
  @Column(name = "author_name")
  private String authorName;

  @KeywordField(projectable = Projectable.YES)
  @Nationalized
  @Column(name = "author_email")
  private String authorEmail;

  @GenericField(projectable = Projectable.YES, sortable = Sortable.YES)
  @Column(name = "author_time")
  private Instant authorTime;

  @KeywordField(projectable = Projectable.YES)
  @Nationalized
  @Column(name = "committer_name")
  private String committerName;

  @KeywordField(projectable = Projectable.YES)
  @Nationalized
  @Column(name = "committer_email")
  private String committerEmail;

  /**
   * Committer timestamp used for default Git-history ordering.
   *
   * <p>The database column retains its historic {@code commit_time} name for schema compatibility.
   */
  @GenericField(projectable = Projectable.YES, sortable = Sortable.YES)
  @Column(name = "commit_time")
  private Instant committerTime;

  @Nationalized
  @Column(name = "changed_paths", length = 16384)
  private String changedPaths;

  @Nationalized
  @Column(name = "changed_text", length = 262144)
  private String changedText;

  public String getProjectionKey() {
    return projectionKey;
  }

  /**
   * Replace the assigned projection key when importing or testing an existing projection.
   *
   * @param projectionKey nonblank persisted projection key
   */
  public void setProjectionKey(String projectionKey) {
    if (projectionKey == null || projectionKey.isBlank()) {
      throw new IllegalArgumentException("projectionKey must not be blank");
    }
    this.projectionKey = projectionKey;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getIndexProfile() {
    return indexProfile;
  }

  public void setIndexProfile(String indexProfile) {
    this.indexProfile = SearchIndexingProfile.fromId(indexProfile).id();
  }

  /** Return the parsed semantic profile that controls derived Lucene fields. */
  @Transient
  public SearchIndexingProfile indexingProfile() {
    return SearchIndexingProfile.fromId(indexProfile);
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getObjectId() {
    return objectId;
  }

  public void setObjectId(String objectId) {
    this.objectId = objectId;
  }

  public String getShortMessage() {
    return shortMessage;
  }

  public void setShortMessage(String shortMessage) {
    this.shortMessage = shortMessage;
  }

  public String getFullMessage() {
    return fullMessage;
  }

  public void setFullMessage(String fullMessage) {
    this.fullMessage = fullMessage;
  }

  public String getAuthorName() {
    return authorName;
  }

  public void setAuthorName(String authorName) {
    this.authorName = authorName;
  }

  public String getAuthorEmail() {
    return authorEmail;
  }

  public void setAuthorEmail(String authorEmail) {
    this.authorEmail = authorEmail;
  }

  public Instant getAuthorTime() {
    return authorTime;
  }

  public void setAuthorTime(Instant authorTime) {
    this.authorTime = authorTime;
  }

  public String getCommitterName() {
    return committerName;
  }

  public void setCommitterName(String committerName) {
    this.committerName = committerName;
  }

  public String getCommitterEmail() {
    return committerEmail;
  }

  public void setCommitterEmail(String committerEmail) {
    this.committerEmail = committerEmail;
  }

  public Instant getCommitterTime() {
    return committerTime;
  }

  public void setCommitterTime(Instant committerTime) {
    this.committerTime = committerTime;
  }

  /**
   * Compatibility alias for the committer timestamp.
   *
   * @return the committer timestamp
   * @deprecated use {@link #getCommitterTime()}; older releases stored author time under this name
   */
  @Deprecated(forRemoval = false)
  public Instant getCommitTime() {
    return getCommitterTime();
  }

  /**
   * Compatibility alias for setting the committer timestamp.
   *
   * @param commitTime committer timestamp
   * @deprecated use {@link #setCommitterTime(Instant)}
   */
  @Deprecated(forRemoval = false)
  public void setCommitTime(Instant commitTime) {
    setCommitterTime(commitTime);
  }

  public String getChangedPaths() {
    return changedPaths;
  }

  public void setChangedPaths(String changedPaths) {
    this.changedPaths = changedPaths;
  }

  /**
   * Return individual changed paths for full-text component matching and exact keyword matching.
   *
   * <p>The historic aggregate {@code changed_paths} value remains relational for literal SQL
   * fragment queries and result detail, but it is no longer indexed as an additional Lucene field.
   * This avoids storing the same path information in three Lucene representations.
   *
   * @return immutable path values, excluding blank lines
   */
  @Transient
  @FullTextField(name = CHANGED_PATH_TERMS_FIELD, analyzer = AnalyzerNames.SIMPLE)
  @KeywordField(name = CHANGED_PATH_EXACT_FIELD)
  @IndexingDependency(
      derivedFrom = {
        @ObjectPath(@PropertyValue(propertyName = "changedPaths")),
        @ObjectPath(@PropertyValue(propertyName = "indexProfile"))
      })
  public List<String> getChangedPathValues() {
    if (!indexingProfile().indexesPaths() || changedPaths == null || changedPaths.isBlank()) {
      return List.of();
    }
    return changedPaths.lines().filter(path -> !path.isBlank()).toList();
  }

  public String getChangedText() {
    return changedText;
  }

  public void setChangedText(String changedText) {
    this.changedText = changedText;
  }

  /** Return changed-file text only for content-enabled profiles. */
  @Transient
  @FullTextField(name = "changedText", analyzer = GitTextAnalysis.STRUCTURED_TEXT_ANALYZER)
  @IndexingDependency(
      derivedFrom = {
        @ObjectPath(@PropertyValue(propertyName = "changedText")),
        @ObjectPath(@PropertyValue(propertyName = "indexProfile"))
      })
  public String getIndexedChangedText() {
    return indexingProfile().indexesContent() ? changedText : null;
  }
}
