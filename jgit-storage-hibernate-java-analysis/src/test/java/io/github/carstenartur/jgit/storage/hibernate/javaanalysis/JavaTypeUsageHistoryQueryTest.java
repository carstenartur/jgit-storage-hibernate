/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Executable semantic use case: identify type users in each repository version. */
class JavaTypeUsageHistoryQueryTest {

  @Test
  void followsMovedTypeAndReportsUsingCodeLocationsPerVersion() {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaAnalysisConfiguration configuration = JavaAnalysisConfiguration.java21BindingAware();

    JavaProjectAnalysisResult first =
        analyzer.analyze(project("v1", versionOneSources()), configuration);
    JavaProjectAnalysisResult second =
        analyzer.analyze(project("v2", versionTwoSources()), configuration);

    JavaTypeUsageHistory history =
        new JavaTypeUsageHistoryQuery()
            .find(List.of(first, second), "demo.policy.ApprovalPolicy")
            .orElseThrow();

    assertEquals(
        List.of("v1", "v2"),
        history.versions().stream().map(JavaTypeUsageHistory.Version::commitId).toList());
    assertEquals("demo.risk.ApprovalPolicy", history.latest().type().getQualifiedName());

    Set<String> firstVersionPaths = paths(history.versions().getFirst());
    Set<String> secondVersionPaths = paths(history.latest());
    assertTrue(firstVersionPaths.contains("src/main/java/demo/checkout/CheckoutService.java"));
    assertTrue(secondVersionPaths.contains("src/main/java/demo/checkout/CheckoutService.java"));
    assertTrue(secondVersionPaths.contains("src/main/java/demo/batch/BatchApprovalJob.java"));

    List<JavaTypeUsageHistory.UsageSite> documentedTypeReferences =
        history.versions().stream()
            .flatMap(version -> version.usageSites().stream())
            .filter(site -> site.relation() == JavaGraphEdgeKind.REFERENCES_TYPE)
            .toList();
    assertFalse(documentedTypeReferences.isEmpty());
    assertTrue(
        documentedTypeReferences.stream()
            .allMatch(site -> site.bindingStatus() == BindingStatus.FULL),
        () ->
            "documented type references must use non-recovered bindings, but were "
                + documentedTypeReferences.stream()
                    .map(JavaTypeUsageHistory.UsageSite::bindingStatus)
                    .toList());
  }

  private static Set<String> paths(JavaTypeUsageHistory.Version version) {
    return version.usageSites().stream()
        .map(JavaTypeUsageHistory.UsageSite::path)
        .collect(Collectors.toSet());
  }

  private static JavaProjectSnapshot project(String commit, Map<String, String> sources) {
    Map<String, JavaSourceSnapshot> snapshots = new LinkedHashMap<>();
    sources.forEach(
        (path, source) ->
            snapshots.put(
                path,
                new JavaSourceSnapshot(
                    "payments", commit, Integer.toHexString(source.hashCode()), path, source)));
    return new JavaProjectSnapshot("payments", commit, snapshots);
  }

  private static Map<String, String> versionOneSources() {
    return Map.of(
        "src/main/java/demo/policy/ApprovalPolicy.java",
        """
        package demo.policy;
        public class ApprovalPolicy {
          public boolean allows(int amount) { return amount < 10000; }
        }
        """,
        "src/main/java/demo/checkout/CheckoutService.java",
        """
        package demo.checkout;
        import demo.policy.ApprovalPolicy;
        public class CheckoutService {
          private final ApprovalPolicy policy = new ApprovalPolicy();
          public boolean checkout(int amount) { return policy.allows(amount); }
        }
        """);
  }

  private static Map<String, String> versionTwoSources() {
    return Map.of(
        "src/main/java/demo/risk/ApprovalPolicy.java",
        """
        package demo.risk;
        public class ApprovalPolicy {
          public boolean allows(int amount) { return amount < 10000; }
        }
        """,
        "src/main/java/demo/checkout/CheckoutService.java",
        """
        package demo.checkout;
        import demo.risk.ApprovalPolicy;
        public class CheckoutService {
          private final ApprovalPolicy policy = new ApprovalPolicy();
          public boolean checkout(int amount) { return policy.allows(amount); }
        }
        """,
        "src/main/java/demo/batch/BatchApprovalJob.java",
        """
        package demo.batch;
        import demo.risk.ApprovalPolicy;
        public class BatchApprovalJob {
          private final ApprovalPolicy policy = new ApprovalPolicy();
          public boolean run(int amount) { return policy.allows(amount); }
        }
        """);
  }
}
