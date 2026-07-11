/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.demo;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisConfiguration;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalysisResult;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaReferenceKind;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSemanticDiff;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSoftwareGraph;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSourceSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSymbolKind;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.SemanticChange;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.SemanticChangeKind;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.SemanticHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Executable demonstration of project analysis and semantic commit comparison. */
public final class SemanticHistoryDemo {

  private SemanticHistoryDemo() {}

  public static void main(String[] args) {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaAnalysisConfiguration configuration = JavaAnalysisConfiguration.java21BindingAware();
    JavaProjectAnalysisResult before = analyzer.analyze(project("before", beforeSources()), configuration);
    JavaProjectAnalysisResult after = analyzer.analyze(project("after", afterSources()), configuration);
    SemanticHistoryQuery beforeQuery = new SemanticHistoryQuery(before);
    SemanticHistoryQuery afterQuery = new SemanticHistoryQuery(after);
    JavaSemanticDiff diff = new JavaSemanticDiff();
    JavaSoftwareGraph afterGraph = JavaSoftwareGraph.from(after);

    System.out.printf("Before: %d symbols, %d references%n", before.symbols().size(), before.references().size());
    System.out.printf("After:  %d symbols, %d references%n", after.symbols().size(), after.references().size());
    System.out.println("Semantic changes:");
    for (SemanticChange change : diff.compare(before, after)) {
      String oldName = change.before() == null ? "-" : change.before().getQualifiedName();
      String newName = change.after() == null ? "-" : change.after().getQualifiedName();
      System.out.printf("  %-22s %s -> %s (%.0f%%, %s)%n",
          change.kind(), oldName, newName, change.confidence() * 100.0, change.evidence());
    }

    System.out.println("// Example 1: Symbols that moved to different packages");
    for (JavaSymbolIndex symbol : beforeQuery.movedSymbols(after).stream()
        .filter(candidate -> candidate.getSymbolKind() == JavaSymbolKind.TYPE)
        .toList()) {
      System.out.printf("  moved: %s in %s%n", symbol.getQualifiedName(), symbol.getPath());
    }

    System.out.println("// Example 2: Methods whose signatures changed");
    for (SemanticChange change : beforeQuery.changesOfKind(after, SemanticChangeKind.SIGNATURE_CHANGED)) {
      System.out.printf("  signature: %s -> %s%n",
          change.before().getSignature(), change.after().getSignature());
    }

    System.out.println("// Example 3: Callers impacted by the Store#save change");
    String saveKey = afterQuery.symbolsNamed("save").stream()
        .map(JavaSymbolIndex::getStableSemanticKey)
        .findFirst()
        .orElse(null);
    Set<String> impacted = saveKey == null ? Set.of() : afterGraph.transitiveImpact(saveKey, 3);
    impacted.stream().sorted().forEach(key -> System.out.printf("  impacted caller: %s%n", key));

    System.out.println("// Example 4: Unresolved reference count before vs after");
    System.out.printf("  unresolved before=%d after=%d%n",
        beforeQuery.unresolvedReferenceCount(), afterQuery.unresolvedReferenceCount());

    System.out.println("// Example 5: Method call count per method name");
    Map<String, Long> callCounts = after.references().stream()
        .filter(reference -> reference.getReferenceKind() == JavaReferenceKind.METHOD_INVOCATION)
        .collect(Collectors.groupingBy(JavaReferenceIndex::getReferenceName, TreeMap::new, Collectors.counting()));
    callCounts.forEach((name, count) -> System.out.printf("  %s -> %d%n", name, count));
  }

  private static JavaProjectSnapshot project(String commit, Map<String, String> sources) {
    Map<String, JavaSourceSnapshot> snapshots = new LinkedHashMap<>();
    sources.forEach((path, source) -> snapshots.put(path,
        new JavaSourceSnapshot("demo", commit, Integer.toHexString(source.hashCode()), path, source)));
    return new JavaProjectSnapshot("demo", commit, snapshots);
  }

  private static Map<String, String> beforeSources() {
    return Map.of(
        "src/main/java/demo/Store.java", """
            package demo;
            public class Store {
              public String save(String value) { return value; }
            }
            """,
        "src/main/java/demo/Client.java", """
            package demo;
            public class Client {
              public String run(Store store) { return store.save("x"); }
            }
            """);
  }

  private static Map<String, String> afterSources() {
    return Map.of(
        "src/main/java/demo/persistence/Store.java", """
            package demo.persistence;
            public class Store {
              public CharSequence save(CharSequence value) { return value; }
            }
            """,
        "src/main/java/demo/Client.java", """
            package demo;
            import demo.persistence.Store;
            public class Client {
              public CharSequence run(Store store) { return store.save("x"); }
            }
            """);
  }
}
