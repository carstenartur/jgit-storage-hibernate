/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.ProjectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Persisted health and freshness state of the Java semantic projection. */
@Entity
@Table(
    name = "java_projection_state",
    indexes = {
      @Index(name = "idx_java_projection_repo_branch", columnList = "repository_name, branch_name"),
      @Index(name = "idx_java_projection_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_java_projection_status", columnList = "status")
    })
public class JavaProjectionState {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Column(name = "branch_name", length = 1024)
  private String branchName;

  @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @Column(name = "projection_version", nullable = false, length = 128)
  private String projectionVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private ProjectionStatus status;

  @Column(name = "indexed_files", nullable = false)
  private int indexedFiles;

  @Column(name = "failed_files", nullable = false)
  private int failedFiles;

  @Column(name = "symbol_count", nullable = false)
  private int symbolCount;

  @Column(name = "reference_count", nullable = false)
  private int referenceCount;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "diagnostic_summary", length = 8192)
  private String diagnosticSummary;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getBranchName() { return branchName; }
  public void setBranchName(String branchName) { this.branchName = branchName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public String getProjectionVersion() { return projectionVersion; }
  public void setProjectionVersion(String projectionVersion) { this.projectionVersion = projectionVersion; }
  public ProjectionStatus getStatus() { return status; }
  public void setStatus(ProjectionStatus status) { this.status = status; }
  public int getIndexedFiles() { return indexedFiles; }
  public void setIndexedFiles(int indexedFiles) { this.indexedFiles = indexedFiles; }
  public int getFailedFiles() { return failedFiles; }
  public void setFailedFiles(int failedFiles) { this.failedFiles = failedFiles; }
  public int getSymbolCount() { return symbolCount; }
  public void setSymbolCount(int symbolCount) { this.symbolCount = symbolCount; }
  public int getReferenceCount() { return referenceCount; }
  public void setReferenceCount(int referenceCount) { this.referenceCount = referenceCount; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
  public String getDiagnosticSummary() { return diagnosticSummary; }
  public void setDiagnosticSummary(String diagnosticSummary) { this.diagnosticSummary = diagnosticSummary; }
}
