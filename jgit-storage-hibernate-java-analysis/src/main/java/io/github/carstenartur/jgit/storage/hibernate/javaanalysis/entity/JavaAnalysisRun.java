/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.BindingMode;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisStatus;
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
import org.hibernate.annotations.Nationalized;

/** Persisted context for one Java/JDT analysis run. */
@Entity
@Table(
    name = "java_analysis_run",
    indexes = {
      @Index(name = "idx_java_analysis_run_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_java_analysis_run_status", columnList = "status"),
      @Index(name = "idx_java_analysis_run_binding", columnList = "binding_mode")
    })
public class JavaAnalysisRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @Column(name = "analyzer_version", nullable = false, length = 64)
  private String analyzerVersion;

  @Column(name = "jdt_version", nullable = false, length = 64)
  private String jdtVersion;

  @Column(name = "source_level", nullable = false, length = 32)
  private String sourceLevel;

  @Enumerated(EnumType.STRING)
  @Column(name = "binding_mode", nullable = false, length = 32)
  private BindingMode bindingMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private JavaAnalysisStatus status;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "compiler_options_hash", nullable = false, length = 64)
  private String compilerOptionsHash;

  @Column(name = "classpath_hash", nullable = false, length = 64)
  private String classpathHash;

  @Column(name = "sourcepath_hash", nullable = false, length = 64)
  private String sourcepathHash;

  @Column(name = "modulepath_hash", nullable = false, length = 64)
  private String modulepathHash;

  @Column(name = "problem_count", nullable = false)
  private int problemCount;

  @Column(name = "error_count", nullable = false)
  private int errorCount;

  @Nationalized
  @Column(name = "failure_message", length = 8192)
  private String failureMessage;

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

  public String getCommitId() {
    return commitId;
  }

  public void setCommitId(String commitId) {
    this.commitId = commitId;
  }

  public String getAnalyzerVersion() {
    return analyzerVersion;
  }

  public void setAnalyzerVersion(String analyzerVersion) {
    this.analyzerVersion = analyzerVersion;
  }

  public String getJdtVersion() {
    return jdtVersion;
  }

  public void setJdtVersion(String jdtVersion) {
    this.jdtVersion = jdtVersion;
  }

  public String getSourceLevel() {
    return sourceLevel;
  }

  public void setSourceLevel(String sourceLevel) {
    this.sourceLevel = sourceLevel;
  }

  public BindingMode getBindingMode() {
    return bindingMode;
  }

  public void setBindingMode(BindingMode bindingMode) {
    this.bindingMode = bindingMode;
  }

  public JavaAnalysisStatus getStatus() {
    return status;
  }

  public void setStatus(JavaAnalysisStatus status) {
    this.status = status;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public String getCompilerOptionsHash() {
    return compilerOptionsHash;
  }

  public void setCompilerOptionsHash(String compilerOptionsHash) {
    this.compilerOptionsHash = compilerOptionsHash;
  }

  public String getClasspathHash() {
    return classpathHash;
  }

  public void setClasspathHash(String classpathHash) {
    this.classpathHash = classpathHash;
  }

  public String getSourcepathHash() {
    return sourcepathHash;
  }

  public void setSourcepathHash(String sourcepathHash) {
    this.sourcepathHash = sourcepathHash;
  }

  public String getModulepathHash() {
    return modulepathHash;
  }

  public void setModulepathHash(String modulepathHash) {
    this.modulepathHash = modulepathHash;
  }

  public int getProblemCount() {
    return problemCount;
  }

  public void setProblemCount(int problemCount) {
    this.problemCount = problemCount;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int errorCount) {
    this.errorCount = errorCount;
  }

  public String getFailureMessage() {
    return failureMessage;
  }

  public void setFailureMessage(String failureMessage) {
    this.failureMessage = failureMessage;
  }
}
