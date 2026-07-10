/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture.entity;

import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureDriftFinding;
import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureDriftKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Searchable, persisted architecture drift finding at one commit. */
@Entity
@Indexed
@Table(
    name = "architecture_drift_finding_index",
    indexes = {
      @Index(name = "idx_arch_drift_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_arch_drift_kind", columnList = "finding_kind"),
      @Index(name = "idx_arch_drift_rule", columnList = "rule_id")
    })
public class ArchitectureDriftFindingIndex {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField @Column(name = "finding_id", nullable = false, length = 64)
  private String findingId;

  @KeywordField @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @GenericField @Enumerated(EnumType.STRING)
  @Column(name = "finding_kind", nullable = false, length = 64)
  private ArchitectureDriftKind findingKind;

  @KeywordField @Column(name = "rule_id", length = 512)
  private String ruleId;

  @KeywordField @Column(name = "source_element_id", length = 512)
  private String sourceElementId;

  @KeywordField @Column(name = "target_element_id", length = 512)
  private String targetElementId;

  @KeywordField @Column(name = "source_path", length = 1024)
  private String sourcePath;

  @GenericField @Column(name = "source_line")
  private Integer sourceLine;

  @FullTextField @Column(name = "message", nullable = false, length = 8192)
  private String message;

  public static ArchitectureDriftFindingIndex from(
      String repository, String commit, ArchitectureDriftFinding finding) {
    ArchitectureDriftFindingIndex entity = new ArchitectureDriftFindingIndex();
    entity.findingId = finding.id();
    entity.repositoryName = repository;
    entity.commitId = commit;
    entity.findingKind = finding.kind();
    entity.ruleId = finding.ruleId();
    entity.sourceElementId = finding.sourceElementId();
    entity.targetElementId = finding.targetElementId();
    entity.message = finding.message();
    if (finding.observedEdge() != null) {
      entity.sourcePath = finding.observedEdge().sourcePath();
      entity.sourceLine = finding.observedEdge().sourceLine();
    }
    return entity;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getFindingId() { return findingId; }
  public void setFindingId(String findingId) { this.findingId = findingId; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public ArchitectureDriftKind getFindingKind() { return findingKind; }
  public void setFindingKind(ArchitectureDriftKind findingKind) { this.findingKind = findingKind; }
  public String getRuleId() { return ruleId; }
  public void setRuleId(String ruleId) { this.ruleId = ruleId; }
  public String getSourceElementId() { return sourceElementId; }
  public void setSourceElementId(String sourceElementId) { this.sourceElementId = sourceElementId; }
  public String getTargetElementId() { return targetElementId; }
  public void setTargetElementId(String targetElementId) { this.targetElementId = targetElementId; }
  public String getSourcePath() { return sourcePath; }
  public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
  public Integer getSourceLine() { return sourceLine; }
  public void setSourceLine(Integer sourceLine) { this.sourceLine = sourceLine; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
}
