/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture.entity;

import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureEvidence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Searchable, persisted architecture evidence entry at one commit. */
@Entity
@Indexed
@Table(
    name = "architecture_evidence_index",
    indexes = {
      @Index(name = "idx_arch_evidence_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_arch_evidence_subject", columnList = "subject_id")
    })
public class ArchitectureEvidenceIndex {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField @Column(name = "evidence_id", nullable = false, length = 512)
  private String evidenceId;

  @KeywordField @Column(name = "subject_id", nullable = false, length = 512)
  private String subjectId;

  @KeywordField @Column(name = "evidence_kind", nullable = false, length = 128)
  private String evidenceKind;

  @KeywordField @Column(name = "repository_name", length = 255)
  private String repositoryName;

  @KeywordField @Column(name = "commit_id", length = 64)
  private String commitId;

  @KeywordField @Column(name = "path", length = 1024)
  private String path;

  @GenericField @Column(name = "line_number")
  private Integer line;

  @FullTextField @Column(name = "rationale", nullable = false, length = 8192)
  private String rationale;

  @GenericField @Column(name = "confidence", nullable = false)
  private double confidence;

  public static ArchitectureEvidenceIndex from(ArchitectureEvidence evidence) {
    ArchitectureEvidenceIndex entity = new ArchitectureEvidenceIndex();
    entity.evidenceId = evidence.id();
    entity.subjectId = evidence.subjectId();
    entity.evidenceKind = evidence.kind();
    entity.repositoryName = evidence.repositoryName();
    entity.commitId = evidence.commitId();
    entity.path = evidence.path();
    entity.line = evidence.line();
    entity.rationale = evidence.rationale();
    entity.confidence = evidence.confidence();
    return entity;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getEvidenceId() { return evidenceId; }
  public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
  public String getSubjectId() { return subjectId; }
  public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
  public String getEvidenceKind() { return evidenceKind; }
  public void setEvidenceKind(String evidenceKind) { this.evidenceKind = evidenceKind; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }
  public Integer getLine() { return line; }
  public void setLine(Integer line) { this.line = line; }
  public String getRationale() { return rationale; }
  public void setRationale(String rationale) { this.rationale = rationale; }
  public double getConfidence() { return confidence; }
  public void setConfidence(double confidence) { this.confidence = confidence; }
}
