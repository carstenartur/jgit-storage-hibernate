/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.BindingStatus;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaReferenceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Binding-aware searchable projection of a Java reference or usage. */
@Entity
@Indexed
@Table(
    name = "java_reference_index",
    indexes = {
      @Index(name = "idx_java_reference_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_java_reference_path", columnList = "repository_name, path"),
      @Index(name = "idx_java_reference_key", columnList = "target_stable_semantic_key"),
      @Index(name = "idx_java_reference_binding", columnList = "raw_binding_key"),
      @Index(name = "idx_java_reference_kind", columnList = "reference_kind")
    })
public class JavaReferenceIndex {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @GenericField
  @Column(name = "analysis_run_id")
  private Long analysisRunId;

  @KeywordField
  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField
  @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @KeywordField
  @Column(name = "blob_id", nullable = false, length = 64)
  private String blobId;

  @KeywordField
  @Nationalized
  @Column(name = "path", nullable = false, length = 1024)
  private String path;

  @GenericField
  @Enumerated(EnumType.STRING)
  @Column(name = "reference_kind", nullable = false, length = 32)
  private JavaReferenceKind referenceKind;

  @GenericField
  @Enumerated(EnumType.STRING)
  @Column(name = "binding_status", nullable = false, length = 32)
  private BindingStatus bindingStatus;

  @FullTextField
  @KeywordField(name = "referenceName_keyword")
  @Nationalized
  @Column(name = "reference_name", nullable = false, length = 2048)
  private String referenceName;

  @KeywordField
  @Nationalized
  @Column(name = "source_symbol_key", length = 4096)
  private String sourceSymbolKey;

  @KeywordField
  @Column(name = "raw_binding_key", length = 4096)
  private String rawBindingKey;

  @KeywordField
  @Column(name = "declaration_binding_key", length = 4096)
  private String declarationBindingKey;

  @KeywordField
  @Column(name = "target_type_binding_key", length = 4096)
  private String targetTypeBindingKey;

  @KeywordField
  @Column(name = "target_stable_semantic_key", length = 4096)
  private String targetStableSemanticKey;

  @GenericField
  @Column(name = "start_line")
  private int startLine;

  @GenericField
  @Column(name = "end_line")
  private int endLine;

  @GenericField
  @Column(name = "start_position")
  private int startPosition;

  @GenericField
  @Column(name = "source_length")
  private int sourceLength;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getAnalysisRunId() {
    return analysisRunId;
  }

  public void setAnalysisRunId(Long analysisRunId) {
    this.analysisRunId = analysisRunId;
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

  public String getBlobId() {
    return blobId;
  }

  public void setBlobId(String blobId) {
    this.blobId = blobId;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public JavaReferenceKind getReferenceKind() {
    return referenceKind;
  }

  public void setReferenceKind(JavaReferenceKind referenceKind) {
    this.referenceKind = referenceKind;
  }

  public BindingStatus getBindingStatus() {
    return bindingStatus;
  }

  public void setBindingStatus(BindingStatus bindingStatus) {
    this.bindingStatus = bindingStatus;
  }

  public String getReferenceName() {
    return referenceName;
  }

  public void setReferenceName(String referenceName) {
    this.referenceName = referenceName;
  }

  public String getSourceSymbolKey() {
    return sourceSymbolKey;
  }

  public void setSourceSymbolKey(String sourceSymbolKey) {
    this.sourceSymbolKey = sourceSymbolKey;
  }

  public String getRawBindingKey() {
    return rawBindingKey;
  }

  public void setRawBindingKey(String rawBindingKey) {
    this.rawBindingKey = rawBindingKey;
  }

  public String getDeclarationBindingKey() {
    return declarationBindingKey;
  }

  public void setDeclarationBindingKey(String declarationBindingKey) {
    this.declarationBindingKey = declarationBindingKey;
  }

  public String getTargetTypeBindingKey() {
    return targetTypeBindingKey;
  }

  public void setTargetTypeBindingKey(String targetTypeBindingKey) {
    this.targetTypeBindingKey = targetTypeBindingKey;
  }

  public String getTargetStableSemanticKey() {
    return targetStableSemanticKey;
  }

  public void setTargetStableSemanticKey(String targetStableSemanticKey) {
    this.targetStableSemanticKey = targetStableSemanticKey;
  }

  public int getStartLine() {
    return startLine;
  }

  public void setStartLine(int startLine) {
    this.startLine = startLine;
  }

  public int getEndLine() {
    return endLine;
  }

  public void setEndLine(int endLine) {
    this.endLine = endLine;
  }

  public int getStartPosition() {
    return startPosition;
  }

  public void setStartPosition(int startPosition) {
    this.startPosition = startPosition;
  }

  public int getSourceLength() {
    return sourceLength;
  }

  public void setSourceLength(int sourceLength) {
    this.sourceLength = sourceLength;
  }
}
