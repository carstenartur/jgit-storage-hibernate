/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Search projection for a Git commit and its metadata. */
@Entity
@Indexed
@Table(name = "git_commit_index")
public class GitCommitIndex {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField
  @Column(name = "repository_name", nullable = false)
  private String repositoryName;

  @KeywordField
  @Column(name = "commit_id", length = 64, nullable = false)
  private String commitId;

  @FullTextField
  @Nationalized
  @Column(name = "message", length = 8192)
  private String message;

  @KeywordField
  @Column(name = "actor_name")
  private String actorName;

  @GenericField
  @Column(name = "commit_time")
  private Instant commitTime;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getActorName() { return actorName; }
  public void setActorName(String actorName) { this.actorName = actorName; }
  public Instant getCommitTime() { return commitTime; }
  public void setCommitTime(Instant commitTime) { this.commitTime = commitTime; }
}
