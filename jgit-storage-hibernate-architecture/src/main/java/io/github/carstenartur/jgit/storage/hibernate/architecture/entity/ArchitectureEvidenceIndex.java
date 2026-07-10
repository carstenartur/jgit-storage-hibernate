/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture.entity;

import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureEvidence;
import jakarta.persistence.*;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.*;

@Entity @Indexed
@Table(name="architecture_evidence_index", indexes={
    @Index(name="idx_arch_evidence_repo_commit",columnList="repository_name,commit_id"),
    @Index(name="idx_arch_evidence_subject",columnList="subject_id")})
public class ArchitectureEvidenceIndex {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @KeywordField @Column(name="evidence_id",nullable=false,length=512) private String evidenceId;
  @KeywordField @Column(name="subject_id",nullable=false,length=512) private String subjectId;
  @KeywordField @Column(name="evidence_kind",nullable=false,length=128) private String evidenceKind;
  @KeywordField @Column(name="repository_name",length=255) private String repositoryName;
  @KeywordField @Column(name="commit_id",length=64) private String commitId;
  @KeywordField @Column(name="path",length=1024) private String path;
  @GenericField @Column(name="line_number") private Integer line;
  @FullTextField @Column(name="rationale",nullable=false,length=8192) private String rationale;
  @GenericField @Column(name="confidence",nullable=false) private double confidence;

  public static ArchitectureEvidenceIndex from(ArchitectureEvidence evidence){
    ArchitectureEvidenceIndex e=new ArchitectureEvidenceIndex();e.evidenceId=evidence.id();e.subjectId=evidence.subjectId();e.evidenceKind=evidence.kind();e.repositoryName=evidence.repositoryName();e.commitId=evidence.commitId();e.path=evidence.path();e.line=evidence.line();e.rationale=evidence.rationale();e.confidence=evidence.confidence();return e;
  }
  public Long getId(){return id;} public void setId(Long v){id=v;} public String getEvidenceId(){return evidenceId;} public void setEvidenceId(String v){evidenceId=v;} public String getSubjectId(){return subjectId;} public void setSubjectId(String v){subjectId=v;} public String getEvidenceKind(){return evidenceKind;} public void setEvidenceKind(String v){evidenceKind=v;} public String getRepositoryName(){return repositoryName;} public void setRepositoryName(String v){repositoryName=v;} public String getCommitId(){return commitId;} public void setCommitId(String v){commitId=v;} public String getPath(){return path;} public void setPath(String v){path=v;} public Integer getLine(){return line;} public void setLine(Integer v){line=v;} public String getRationale(){return rationale;} public void setRationale(String v){rationale=v;} public double getConfidence(){return confidence;} public void setConfidence(double v){confidence=v;}
}
