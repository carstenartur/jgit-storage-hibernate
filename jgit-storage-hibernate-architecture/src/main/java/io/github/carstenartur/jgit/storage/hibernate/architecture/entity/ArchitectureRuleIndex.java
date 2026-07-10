/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture.entity;

import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureRule;
import io.github.carstenartur.jgit.storage.hibernate.architecture.ArchitectureRuleEffect;
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
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Searchable, persisted versioned architecture rule at one commit. */
@Entity
@Indexed
@Table(
    name = "architecture_rule_index",
    indexes = {
      @Index(name = "idx_arch_rule_repo_commit", columnList = "repository_name, commit_id"),
      @Index(name = "idx_arch_rule_source_target", columnList = "source_element_id, target_element_id")
    })
public class ArchitectureRuleIndex {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @KeywordField @Column(name = "rule_id", nullable = false, length = 512)
  private String ruleId;

  @GenericField @Enumerated(EnumType.STRING)
  @Column(name = "effect", nullable = false, length = 16)
  private ArchitectureRuleEffect effect;

  @GenericField @Enumerated(EnumType.STRING)
  @Column(name = "edge_kind", nullable = false, length = 32)
  private JavaGraphEdgeKind edgeKind;

  @KeywordField @Column(name = "source_element_id", nullable = false, length = 512)
  private String sourceElementId;

  @KeywordField @Column(name = "target_element_id", nullable = false, length = 512)
  private String targetElementId;

  @FullTextField @Column(name = "rationale", length = 8192)
  private String rationale;

  @KeywordField @Column(name = "evidence_id", length = 512)
  private String evidenceId;

  public static ArchitectureRuleIndex from(String repository, String commit, ArchitectureRule rule) {
    ArchitectureRuleIndex entity = new ArchitectureRuleIndex();
    entity.repositoryName = repository;
    entity.commitId = commit;
    entity.ruleId = rule.id();
    entity.effect = rule.effect();
    entity.edgeKind = rule.edgeKind();
    entity.sourceElementId = rule.sourceElementId();
    entity.targetElementId = rule.targetElementId();
    entity.rationale = rule.rationale();
    entity.evidenceId = rule.evidenceId();
    return entity;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
  public String getCommitId() { return commitId; }
  public void setCommitId(String commitId) { this.commitId = commitId; }
  public String getRuleId() { return ruleId; }
  public void setRuleId(String ruleId) { this.ruleId = ruleId; }
  public ArchitectureRuleEffect getEffect() { return effect; }
  public void setEffect(ArchitectureRuleEffect effect) { this.effect = effect; }
  public JavaGraphEdgeKind getEdgeKind() { return edgeKind; }
  public void setEdgeKind(JavaGraphEdgeKind edgeKind) { this.edgeKind = edgeKind; }
  public String getSourceElementId() { return sourceElementId; }
  public void setSourceElementId(String sourceElementId) { this.sourceElementId = sourceElementId; }
  public String getTargetElementId() { return targetElementId; }
  public void setTargetElementId(String targetElementId) { this.targetElementId = targetElementId; }
  public String getRationale() { return rationale; }
  public void setRationale(String rationale) { this.rationale = rationale; }
  public String getEvidenceId() { return evidenceId; }
  public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
}
