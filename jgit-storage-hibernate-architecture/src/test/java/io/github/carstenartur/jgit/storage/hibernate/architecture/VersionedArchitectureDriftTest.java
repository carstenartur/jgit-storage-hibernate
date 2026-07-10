/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisConfiguration;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSoftwareGraph;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSourceSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VersionedArchitectureDriftTest {

  @Test
  void detectsForbiddenCodeRelationAndKeepsEvidence() {
    ArchitectureSnapshot architecture = parse("arch-a", """
        element ui layer "UI" packagePrefix=demo.ui
        element database layer "Database" packagePrefix=demo.db
        rule no-ui-db forbid REFERENCES_TYPE from ui to database evidence=adr-7 reason="UI must not access database"
        evidence adr-7 for no-ui-db kind=ADR path=docs/adr/0007.md rationale="Layering decision" confidence=1.0
        """);

    JavaSoftwareGraph graph = JavaSoftwareGraph.from(
        new JavaProjectAnalyzer().analyze(project(), JavaAnalysisConfiguration.java21BindingAware()));
    ArchitectureDriftReport report = new ArchitectureDriftEngine().evaluate(architecture, graph);

    assertTrue(report.findings().stream().anyMatch(finding -> finding.kind() == ArchitectureDriftKind.FORBIDDEN_RELATION));
    assertTrue(report.findings().stream().filter(finding -> finding.kind() == ArchitectureDriftKind.FORBIDDEN_RELATION)
        .allMatch(finding -> finding.evidenceIds().contains("adr-7")));
  }

  @Test
  void comparesArchitectureSnapshotsByStableIds() {
    ArchitectureSnapshot before = parse("a", """
        element api layer "API" packagePrefix=demo.api
        element domain layer "Domain" packagePrefix=demo.domain
        rule api-domain require REFERENCES_TYPE from api to domain reason="API delegates to domain"
        """);
    ArchitectureSnapshot after = parse("b", """
        element api layer "Public API" packagePrefix=demo.api
        element domain layer "Domain" packagePrefix=demo.domain
        element persistence layer "Persistence" packagePrefix=demo.persistence
        rule api-domain forbid REFERENCES_TYPE from api to domain reason="API must use application layer"
        """);

    var changes = new ArchitectureSemanticDiff().compare(before, after);
    assertTrue(changes.stream().anyMatch(change -> change.kind() == ArchitectureChangeKind.ELEMENT_CHANGED));
    assertTrue(changes.stream().anyMatch(change -> change.kind() == ArchitectureChangeKind.ELEMENT_ADDED));
    assertTrue(changes.stream().anyMatch(change -> change.kind() == ArchitectureChangeKind.RULE_CHANGED));
  }

  @Test
  void reportsMissingRequiredRelationAndMissingEvidence() {
    ArchitectureSnapshot architecture = parse("arch-b", """
        element ui layer "UI" packagePrefix=demo.ui
        element database layer "Database" packagePrefix=demo.db
        rule required require CALLS from ui to database evidence=missing reason="Required integration"
        """);
    JavaSoftwareGraph graph = JavaSoftwareGraph.from(
        new JavaProjectAnalyzer().analyze(project(), JavaAnalysisConfiguration.java21BindingAware()));
    ArchitectureDriftReport report = new ArchitectureDriftEngine().evaluate(architecture, graph);
    assertTrue(report.findings().stream().anyMatch(finding -> finding.kind() == ArchitectureDriftKind.MISSING_REQUIRED_RELATION));
    assertTrue(report.findings().stream().anyMatch(finding -> finding.kind() == ArchitectureDriftKind.MISSING_EVIDENCE));
  }

  private static ArchitectureSnapshot parse(String commit, String content) {
    return new SimpleArchitectureDslParser().parse(
        new ArchitectureDslSource("demo", commit, "architecture/system.architecture", content)).snapshot();
  }

  private static JavaProjectSnapshot project() {
    Map<String, String> sources = Map.of(
        "src/main/java/demo/db/Repository.java", """
            package demo.db;
            public class Repository { public void save() {} }
            """,
        "src/main/java/demo/ui/Screen.java", """
            package demo.ui;
            import demo.db.Repository;
            public class Screen { private Repository repository; }
            """);
    Map<String, JavaSourceSnapshot> snapshots = new LinkedHashMap<>();
    sources.forEach((path, source) -> snapshots.put(path,
        new JavaSourceSnapshot("demo", "code-a", Integer.toHexString(source.hashCode()), path, source)));
    return new JavaProjectSnapshot("demo", "code-a", snapshots);
  }
}
