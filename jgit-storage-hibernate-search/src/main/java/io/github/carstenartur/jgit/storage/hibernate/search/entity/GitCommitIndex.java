/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.engine.backend.analysis.AnalyzerNames;
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
      @Index(name = "idx_commit_repo_author", columnList = "repository_name, author_email")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_commit_repo_object",
          columnNames = {"repository_name", "object_id"})
    })
public class GitCommitIndex {

  /** Full-text field containing lowercase path components split at punctuation. */
  public static final String CHANGED_PATH_TERMS_FIELD = "changedPathTerms";

  /** Keyword field containing one exact value per changed path. */
  public static final String CHANGED_PATH_EXACT_FIELD = "changedPathExact";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField
  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField
  @Column(name = "object_id", nullable = false, length = 40)
  private String objectId;

  @FullTextField
  @Nationalized
  @Column(name = "short_message", length = 2048)
  private String shortMessage;

  @FullTextField
  @Nationalized
  @Column(name = "full_message", length = 8192)
  private String fullMessage;

  @KeywordField
  @Nationalized
  @Column(name = "author_name")
  private String authorName;

  @KeywordField
  @Nationalized
  @Column(name = "author_email")
  private String authorEmail;

  @GenericField
  @Column(name = "commit_time")
  private Instant commitTime;

  @FullTextField
  @Nationalized
  @Column(name = "changed_paths", length = 16384)
  private String changedPaths;

  @FullTextField
  @Nationalized
  @Column(name = "changed_text", length = 262144)
  private String changedText;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
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

  public Instant getCommitTime() {
    return commitTime;
  }

  public void setCommitTime(Instant commitTime) {
    this.commitTime = commitTime;
  }

  public String getChangedPaths() {
    return changedPaths;
  }

  public void setChangedPaths(String changedPaths) {
    this.changedPaths = changedPaths;
  }

  /**
   * Return individual changed paths for field-specific full-text and exact indexing.
   *
   * @return immutable path values, excluding blank lines
   */
  @Transient
  @FullTextField(name = CHANGED_PATH_TERMS_FIELD, analyzer = AnalyzerNames.SIMPLE)
  @KeywordField(name = CHANGED_PATH_EXACT_FIELD)
  @IndexingDependency(
      derivedFrom = @ObjectPath(@PropertyValue(propertyName = "changedPaths")))
  public List<String> getChangedPathValues() {
    if (changedPaths == null || changedPaths.isBlank()) {
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
}
