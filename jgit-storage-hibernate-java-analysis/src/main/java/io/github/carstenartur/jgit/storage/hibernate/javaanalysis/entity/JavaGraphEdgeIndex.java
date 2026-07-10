/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.BindingStatus;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdge;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdgeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Searchable, persisted relation in the software graph of one commit. */
@Entity
@Indexed
@Table(
    name = "java_graph_edge_index",
    indexes = {
      @Index(name = "idx_java_graph_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_java_graph_source", columnList = "source_semantic_key"),
      @Index(name = "idx_java_graph_target", columnList = "target_semantic_key"),
      @Index(name = "idx_java_graph_kind", columnList = "edge_kind")
    })
public class JavaGraphEdgeIndex {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @GenericField @Column(name = "analysis_run_id")
  private Long analysisRunId;

  @KeywordField @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @GenericField @Enumerated(EnumType.STRING)
  @Column(name = "edge_kind", nullable = false, length = 32)
  private JavaGraphEdgeKind edgeKind;

  @KeywordField @Column(name = "source_semantic_key", nullable = false, length = 4096)
  private String sourceSemanticKey;

  @KeywordField @Column(name = "target_semantic_key", nullable = false, length = 4096)
  private String targetSemanticKey;

  @KeywordField @Column(name = "source_path", length = 1024)
  private String sourcePath;

  @GenericField @Column(name = "source_line")
  private int sourceLine;

  @GenericField @Enumerated(EnumType.STRING)
  @Column(name = "binding_status", nullable = false, length = 32)
  private BindingStatus bindingStatus;

  public static JavaGraphEdgeIndex from(JavaGraphEdge edge) {
    JavaGraphEdgeIndex entity = new JavaGraphEdgeIndex();
    entity.repositoryName = edge.repositoryName();
    entity.commitId = edge.commitId();
    entity.edgeKind = edge.kind();
    entity.sourceSemanticKey = edge.sourceSemanticKey();
    entity.targetSemanticKey = edge.targetSemanticKey();
    entity.sourcePath = edge.sourcePath();
    entity.sourceLine = edge.sourceLine();
    entity.bindingStatus = edge.bindingStatus();
    return entity;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAnalysisRunId() { return analysisRunId; }
  public void setAnalysisRunId(Long analysisRunId) { this.analysisRunId = analysisRunId; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public JavaGraphEdgeKind getEdgeKind() { return edgeKind; }
  public void setEdgeKind(JavaGraphEdgeKind edgeKind) { this.edgeKind = edgeKind; }
  public String getSourceSemanticKey() { return sourceSemanticKey; }
  public void setSourceSemanticKey(String value) { this.sourceSemanticKey = value; }
  public String getTargetSemanticKey() { return targetSemanticKey; }
  public void setTargetSemanticKey(String value) { this.targetSemanticKey = value; }
  public String getSourcePath() { return sourcePath; }
  public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
  public int getSourceLine() { return sourceLine; }
  public void setSourceLine(int sourceLine) { this.sourceLine = sourceLine; }
  public BindingStatus getBindingStatus() { return bindingStatus; }
  public void setBindingStatus(BindingStatus bindingStatus) { this.bindingStatus = bindingStatus; }
}
