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
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSymbolKind;
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

/** Binding-aware searchable projection of a Java declaration. */
@Entity
@Indexed
@Table(
    name = "java_symbol_index",
    indexes = {
      @Index(name = "idx_java_symbol_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_java_symbol_path", columnList = "repository_name, path"),
      @Index(name = "idx_java_symbol_key", columnList = "stable_semantic_key"),
      @Index(name = "idx_java_symbol_binding", columnList = "raw_binding_key"),
      @Index(name = "idx_java_symbol_kind", columnList = "symbol_kind")
    })
public class JavaSymbolIndex {

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
  @Column(name = "symbol_kind", nullable = false, length = 32)
  private JavaSymbolKind symbolKind;

  @GenericField
  @Enumerated(EnumType.STRING)
  @Column(name = "binding_status", nullable = false, length = 32)
  private BindingStatus bindingStatus;

  @KeywordField
  @Nationalized
  @Column(name = "package_name", length = 1024)
  private String packageName;

  @FullTextField
  @KeywordField(name = "simpleName_keyword")
  @Nationalized
  @Column(name = "simple_name", nullable = false, length = 512)
  private String simpleName;

  @FullTextField
  @KeywordField(name = "qualifiedName_keyword")
  @Nationalized
  @Column(name = "qualified_name", length = 2048)
  private String qualifiedName;

  @KeywordField
  @Nationalized
  @Column(name = "declaring_type", length = 2048)
  private String declaringType;

  @FullTextField
  @KeywordField(name = "signature_keyword")
  @Nationalized
  @Column(name = "signature", length = 4096)
  private String signature;

  @KeywordField
  @Nationalized
  @Column(name = "return_type", length = 2048)
  private String returnType;

  @FullTextField
  @Nationalized
  @Column(name = "parameter_types", length = 8192)
  private String parameterTypes;

  @FullTextField
  @Nationalized
  @Column(name = "modifiers", length = 2048)
  private String modifiers;

  @FullTextField
  @Nationalized
  @Column(name = "annotations", length = 8192)
  private String annotations;

  @KeywordField
  @Column(name = "raw_binding_key", length = 4096)
  private String rawBindingKey;

  @KeywordField
  @Column(name = "declaration_binding_key", length = 4096)
  private String declarationBindingKey;

  @KeywordField
  @Column(name = "declaring_type_binding_key", length = 4096)
  private String declaringTypeBindingKey;

  @KeywordField
  @Column(name = "type_binding_key", length = 4096)
  private String typeBindingKey;

  @KeywordField
  @Column(name = "stable_semantic_key", length = 4096)
  private String stableSemanticKey;

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

  public JavaSymbolKind getSymbolKind() {
    return symbolKind;
  }

  public void setSymbolKind(JavaSymbolKind symbolKind) {
    this.symbolKind = symbolKind;
  }

  public BindingStatus getBindingStatus() {
    return bindingStatus;
  }

  public void setBindingStatus(BindingStatus bindingStatus) {
    this.bindingStatus = bindingStatus;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public String getSimpleName() {
    return simpleName;
  }

  public void setSimpleName(String simpleName) {
    this.simpleName = simpleName;
  }

  public String getQualifiedName() {
    return qualifiedName;
  }

  public void setQualifiedName(String qualifiedName) {
    this.qualifiedName = qualifiedName;
  }

  public String getDeclaringType() {
    return declaringType;
  }

  public void setDeclaringType(String declaringType) {
    this.declaringType = declaringType;
  }

  public String getSignature() {
    return signature;
  }

  public void setSignature(String signature) {
    this.signature = signature;
  }

  public String getReturnType() {
    return returnType;
  }

  public void setReturnType(String returnType) {
    this.returnType = returnType;
  }

  public String getParameterTypes() {
    return parameterTypes;
  }

  public void setParameterTypes(String parameterTypes) {
    this.parameterTypes = parameterTypes;
  }

  public String getModifiers() {
    return modifiers;
  }

  public void setModifiers(String modifiers) {
    this.modifiers = modifiers;
  }

  public String getAnnotations() {
    return annotations;
  }

  public void setAnnotations(String annotations) {
    this.annotations = annotations;
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

  public String getDeclaringTypeBindingKey() {
    return declaringTypeBindingKey;
  }

  public void setDeclaringTypeBindingKey(String declaringTypeBindingKey) {
    this.declaringTypeBindingKey = declaringTypeBindingKey;
  }

  public String getTypeBindingKey() {
    return typeBindingKey;
  }

  public void setTypeBindingKey(String typeBindingKey) {
    this.typeBindingKey = typeBindingKey;
  }

  public String getStableSemanticKey() {
    return stableSemanticKey;
  }

  public void setStableSemanticKey(String stableSemanticKey) {
    this.stableSemanticKey = stableSemanticKey;
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
