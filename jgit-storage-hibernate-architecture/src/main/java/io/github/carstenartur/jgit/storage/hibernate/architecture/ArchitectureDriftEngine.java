/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdge;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSoftwareGraph;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compares observed code graph relations with versioned architecture intent. */
public final class ArchitectureDriftEngine {

  public ArchitectureDriftReport evaluate(ArchitectureSnapshot architecture, JavaSoftwareGraph graph) {
    ArchitectureCodeMapping mapping = new ArchitectureCodeMapper().map(architecture, graph);
    List<ArchitectureDriftFinding> findings = new ArrayList<>();

    mapping.unmappedSemanticKeys().forEach(key -> findings.add(finding(
        ArchitectureDriftKind.UNMAPPED_CODE_SYMBOL, null, null, null, null,
        "No architecture element matches code symbol " + key, List.of())));
    mapping.ambiguousElementIds().forEach((key, ids) -> findings.add(finding(
        ArchitectureDriftKind.AMBIGUOUS_MAPPING, null, null, null, null,
        "Code symbol " + key + " matches multiple architecture elements " + ids, List.of())));

    Set<String> observedRuleKeys = new HashSet<>();
    for (JavaGraphEdge edge : graph.edges()) {
      String sourceElement = mapping.elementFor(edge.sourceSemanticKey());
      String targetElement = mapping.elementFor(edge.targetSemanticKey());
      if (sourceElement == null || targetElement == null) continue;
      observedRuleKeys.add(ruleKey(edge.kind().name(), sourceElement, targetElement));
      for (ArchitectureRule rule : architecture.rules()) {
        if (matches(rule, edge, sourceElement, targetElement) && rule.effect() == ArchitectureRuleEffect.FORBID) {
          findings.add(finding(
              ArchitectureDriftKind.FORBIDDEN_RELATION, rule.id(), sourceElement, targetElement, edge,
              "Observed " + edge.kind() + " violates rule " + rule.id() + ": " + rule.rationale(),
              evidenceIds(architecture, rule)));
        }
      }
    }

    for (ArchitectureRule rule : architecture.rules()) {
      List<String> evidenceIds = evidenceIds(architecture, rule);
      if (rule.evidenceId() != null && evidenceIds.isEmpty()) {
        findings.add(finding(
            ArchitectureDriftKind.MISSING_EVIDENCE, rule.id(), rule.sourceElementId(), rule.targetElementId(), null,
            "Rule " + rule.id() + " references missing evidence " + rule.evidenceId(), List.of()));
      }
      if (rule.effect() == ArchitectureRuleEffect.REQUIRE
          && !observedRuleKeys.contains(ruleKey(rule.edgeKind().name(), rule.sourceElementId(), rule.targetElementId()))) {
        findings.add(finding(
            ArchitectureDriftKind.MISSING_REQUIRED_RELATION, rule.id(), rule.sourceElementId(), rule.targetElementId(), null,
            "Required relation " + rule.edgeKind() + " from " + rule.sourceElementId() + " to " + rule.targetElementId() + " is absent",
            evidenceIds));
      }
    }

    for (ArchitectureEvidence evidence : architecture.evidence()) {
      String validThrough = evidence.attributes().get("validThroughCommit");
      if (validThrough != null && !validThrough.equals(graph.analysis().project().commitId())) {
        findings.add(finding(
            ArchitectureDriftKind.STALE_EVIDENCE, null, evidence.subjectId(), null, null,
            "Evidence " + evidence.id() + " was validated through " + validThrough
                + " but code graph is at " + graph.analysis().project().commitId(), List.of(evidence.id())));
      }
    }
    return new ArchitectureDriftReport(architecture, mapping, findings);
  }

  private static boolean matches(
      ArchitectureRule rule, JavaGraphEdge edge, String sourceElement, String targetElement) {
    return rule.edgeKind() == edge.kind()
        && rule.sourceElementId().equals(sourceElement)
        && rule.targetElementId().equals(targetElement);
  }

  private static List<String> evidenceIds(ArchitectureSnapshot architecture, ArchitectureRule rule) {
    if (rule.evidenceId() == null) return List.of();
    return architecture.evidence().stream().map(ArchitectureEvidence::id)
        .filter(rule.evidenceId()::equals).toList();
  }

  private static String ruleKey(String kind, String source, String target) {
    return kind + "|" + source + "|" + target;
  }

  private static ArchitectureDriftFinding finding(
      ArchitectureDriftKind kind,
      String ruleId,
      String source,
      String target,
      JavaGraphEdge edge,
      String message,
      List<String> evidenceIds) {
    return new ArchitectureDriftFinding(
        hash(kind + "|" + ruleId + "|" + source + "|" + target + "|" + message),
        kind, ruleId, source, target, edge, message, evidenceIds);
  }

  private static String hash(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : bytes) result.append(String.format("%02x", b));
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
