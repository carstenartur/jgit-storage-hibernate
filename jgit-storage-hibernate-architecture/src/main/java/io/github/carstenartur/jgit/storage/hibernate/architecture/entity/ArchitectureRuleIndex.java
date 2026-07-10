/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture.entity;

import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureRule;
import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureRuleEffect;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdgeKind;
import jakarta.persistence.*;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.*;

@Entity @Indexed
@Table(name="architecture_rule_index", indexes={
    @Index(name="idx_arch_rule_repo_commit", columnList="repository_name, commit_id"),
    @Index(name="idx_arch_rule_source_target", columnList="source_element_id, target_element_id")})
public class ArchitectureRuleIndex {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @KeywordField @Column(name="repository_name",nullable=false,length=255) private String repositoryName;
  @KeywordField @Column(name="commit_id",nullable=false,length=64) private String commitId;
  @KeywordField @Column(name="rule_id",nullable=false,length=512) private String ruleId;
  @GenericField @Enumerated(EnumType.STRING) @Column(name="effect",nullable=false,length=16) private ArchitectureRuleEffect effect;
  @GenericField @Enumerated(EnumType.STRING) @Column(name="edge_kind",nullable=false,length=32) private JavaGraphEdgeKind edgeKind;
  @KeywordField @Column(name="source_element_id",nullable=false,length=512) private String sourceElementId;
  @KeywordField @Column(name="target_element_id",nullable=false,length=512) private String targetElementId;
  @FullTextField @Column(name="rationale",length=8192) private String rationale;
  @KeywordField @Column(name="evidence_id",length=512) private String evidenceId;

  public static ArchitectureRuleIndex from(String repository, String commit, ArchitectureRule rule) {
    ArchitectureRuleIndex e=new ArchitectureRuleIndex(); e.repositoryName=repository;e.commitId=commit;e.ruleId=rule.id();e.effect=rule.effect();e.edgeKind=rule.edgeKind();e.sourceElementId=rule.sourceElementId();e.targetElementId=rule.targetElementId();e.rationale=rule.rationale();e.evidenceId=rule.evidenceId();return e;
  }
  public Long getId(){return id;} public void setId(Long v){id=v;} public String getRepositoryName(){return repositoryName;} public void setRepositoryName(String v){repositoryName=v;} public String getCommitId(){return commitId;} public void setCommitId(String v){commitId=v;} public String getRuleId(){return ruleId;} public void setRuleId(String v){ruleId=v;} public ArchitectureRuleEffect getEffect(){return effect;} public void setEffect(ArchitectureRuleEffect v){effect=v;} public JavaGraphEdgeKind getEdgeKind(){return edgeKind;} public void setEdgeKind(JavaGraphEdgeKind v){edgeKind=v;} public String getSourceElementId(){return sourceElementId;} public void setSourceElementId(String v){sourceElementId=v;} public String getTargetElementId(){return targetElementId;} public void setTargetElementId(String v){targetElementId=v;} public String getRationale(){return rationale;} public void setRationale(String v){rationale=v;} public String getEvidenceId(){return evidenceId;} public void setEvidenceId(String v){evidenceId=v;}
}
