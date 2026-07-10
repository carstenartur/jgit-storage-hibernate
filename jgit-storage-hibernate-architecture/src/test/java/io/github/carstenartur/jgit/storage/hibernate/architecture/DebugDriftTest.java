/* Debug test - temporary */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DebugDriftTest {
  @Test
  void debugGraph() {
    ArchitectureSnapshot architecture = parse("arch-a", """
        element ui layer "UI" packagePrefix=demo.ui
        element database layer "Database" packagePrefix=demo.db
        rule no-ui-db forbid REFERENCES_TYPE from ui to database evidence=adr-7 reason="UI must not access database"
        evidence adr-7 for no-ui-db kind=ADR path=docs/adr/0007.md rationale="Layering decision" confidence=1.0
        """);

    JavaSoftwareGraph graph = JavaSoftwareGraph.from(
        new JavaProjectAnalyzer().analyze(project(), JavaAnalysisConfiguration.java21BindingAware()));
    
    System.out.println("=== SYMBOLS (" + graph.symbols().size() + ") ===");
    graph.symbols().forEach((k, v) -> System.out.println("  " + k + " pkg=" + v.getPackageName()));
    
    System.out.println("=== EDGES (" + graph.edges().size() + ") ===");
    graph.edges().forEach(e -> System.out.println("  " + e.kind() + " src=" + e.sourceSemanticKey() + " tgt=" + e.targetSemanticKey()));
    
    ArchitectureCodeMapping mapping = new ArchitectureCodeMapper().map(architecture, graph);
    System.out.println("=== MAPPING (" + mapping.elementBySemanticKey().size() + ") ===");
    mapping.elementBySemanticKey().forEach((k, v) -> System.out.println("  " + k + " -> " + v));
    
    ArchitectureDriftReport report = new ArchitectureDriftEngine().evaluate(architecture, graph);
    System.out.println("=== FINDINGS (" + report.findings().size() + ") ===");
    report.findings().forEach(f -> System.out.println("  " + f.kind() + " " + f.message()));
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
