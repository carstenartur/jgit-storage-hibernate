/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture.entity;

import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureDriftFinding;
import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureDriftKind;
import jakarta.persistence.*;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.*;

@Entity @Indexed
@Table(name="architecture_drift_finding_index", indexes={
    @Index(name="idx_arch_drift_repo_commit",columnList="repository_name,commit_id"),
    @Index(name="idx_arch_drift_kind",columnList="finding_kind"),
    @Index(name="idx_arch_drift_rule",columnList="rule_id")})
public class ArchitectureDriftFindingIndex {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @KeywordField @Column(name="finding_id",nullable=false,length=64) private String findingId;
  @KeywordField @Column(name="repository_name",nullable=false,length=255) private String repositoryName;
  @KeywordField @Column(name="commit_id",nullable=false,length=64) private String commitId;
  @GenericField @Enumerated(EnumType.STRING) @Column(name="finding_kind",nullable=false,length=64) private ArchitectureDriftKind findingKind;
  @KeywordField @Column(name="rule_id",length=512) private String ruleId;
  @KeywordField @Column(name="source_element_id",length=512) private String sourceElementId;
  @KeywordField @Column(name="target_element_id",length=512) private String targetElementId;
  @KeywordField @Column(name="source_path",length=1024) private String sourcePath;
  @GenericField @Column(name="source_line") private Integer sourceLine;
  @FullTextField @Column(name="message",nullable=false,length=8192) private String message;

  public static ArchitectureDriftFindingIndex from(String repository,String commit,ArchitectureDriftFinding finding){
    ArchitectureDriftFindingIndex e=new ArchitectureDriftFindingIndex();e.findingId=finding.id();e.repositoryName=repository;e.commitId=commit;e.findingKind=finding.kind();e.ruleId=finding.ruleId();e.sourceElementId=finding.sourceElementId();e.targetElementId=finding.targetElementId();e.message=finding.message();if(finding.observedEdge()!=null){e.sourcePath=finding.observedEdge().sourcePath();e.sourceLine=finding.observedEdge().sourceLine();}return e;
  }
  public Long getId(){return id;} public void setId(Long v){id=v;} public String getFindingId(){return findingId;} public void setFindingId(String v){findingId=v;} public String getRepositoryName(){return repositoryName;} public void setRepositoryName(String v){repositoryName=v;} public String getCommitId(){return commitId;} public void setCommitId(String v){commitId=v;} public ArchitectureDriftKind getFindingKind(){return findingKind;} public void setFindingKind(ArchitectureDriftKind v){findingKind=v;} public String getRuleId(){return ruleId;} public void setRuleId(String v){ruleId=v;} public String getSourceElementId(){return sourceElementId;} public void setSourceElementId(String v){sourceElementId=v;} public String getTargetElementId(){return targetElementId;} public void setTargetElementId(String v){targetElementId=v;} public String getSourcePath(){return sourcePath;} public void setSourcePath(String v){sourcePath=v;} public Integer getSourceLine(){return sourceLine;} public void setSourceLine(Integer v){sourceLine=v;} public String getMessage(){return message;} public void setMessage(String v){message=v;}
}
