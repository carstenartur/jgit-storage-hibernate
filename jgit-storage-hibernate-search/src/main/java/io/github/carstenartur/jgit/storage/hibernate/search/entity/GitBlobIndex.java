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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Search projection for a text blob found in a Git tree. */
@Entity
@Indexed
@Table(name = "git_blob_index")
public class GitBlobIndex {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField
  @Column(name = "repository_name", nullable = false)
  private String repositoryName;

  @KeywordField
  @Column(name = "commit_id", length = 64, nullable = false)
  private String commitId;

  @KeywordField
  @Column(name = "object_id", length = 64, nullable = false)
  private String objectId;

  @FullTextField
  @Nationalized
  @Column(name = "path", length = 4096, nullable = false)
  private String path;

  @FullTextField
  @Nationalized
  @Lob
  @Column(name = "content")
  private String content;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public String getObjectId() { return objectId; }
  public void setObjectId(String objectId) { this.objectId = objectId; }
  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
}
