/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.demo;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisConfiguration;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalysisResult;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSemanticDiff;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSourceSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.SemanticChange;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executable demonstration of project analysis and semantic commit comparison. */
public final class SemanticHistoryDemo {

  private SemanticHistoryDemo() {}

  public static void main(String[] args) {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaAnalysisConfiguration configuration = JavaAnalysisConfiguration.java21BindingAware();
    JavaProjectAnalysisResult before = analyzer.analyze(project("before", beforeSources()), configuration);
    JavaProjectAnalysisResult after = analyzer.analyze(project("after", afterSources()), configuration);

    System.out.printf("Before: %d symbols, %d references%n", before.symbols().size(), before.references().size());
    System.out.printf("After:  %d symbols, %d references%n", after.symbols().size(), after.references().size());
    System.out.println("Semantic changes:");
    for (SemanticChange change : new JavaSemanticDiff().compare(before, after)) {
      String oldName = change.before() == null ? "-" : change.before().getQualifiedName();
      String newName = change.after() == null ? "-" : change.after().getQualifiedName();
      System.out.printf("  %-22s %s -> %s (%.0f%%, %s)%n",
          change.kind(), oldName, newName, change.confidence() * 100.0, change.evidence());
    }
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
