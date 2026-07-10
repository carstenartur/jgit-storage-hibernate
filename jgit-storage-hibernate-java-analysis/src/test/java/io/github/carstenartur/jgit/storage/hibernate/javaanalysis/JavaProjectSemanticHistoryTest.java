/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaProjectSemanticHistoryTest {

  @Test
  void resolvesReferencesAcrossCompilationUnitsAndDetectsSignatureChange() {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaProjectAnalysisResult before = analyzer.analyze(project("a", "String"), JavaAnalysisConfiguration.java21BindingAware());
    JavaProjectAnalysisResult after = analyzer.analyze(project("b", "CharSequence"), JavaAnalysisConfiguration.java21BindingAware());

    assertFalse(before.symbols().isEmpty());
    assertFalse(new SemanticHistoryQuery(before).methodInvocationsNamed("save").isEmpty());
    assertTrue(new JavaSemanticDiff().compare(before, after).stream()
        .anyMatch(change -> change.kind() == SemanticChangeKind.SIGNATURE_CHANGED));
  }

  @Test
  void readsMavenSourceLevelModulesAndUnresolvedDependencies() {
    String pom = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <properties><maven.compiler.release>21</maven.compiler.release></properties>
          <modules><module>api</module></modules>
          <dependencies>
            <dependency><groupId>invalid.example</groupId><artifactId>missing</artifactId><version>1.0</version></dependency>
          </dependencies>
        </project>
        """;
    var resolution = new MavenJavaAnalysisConfigurationResolver().resolve(Map.of("pom.xml", pom));
    assertTrue(resolution.modules().contains("api"));
    assertTrue(resolution.sourceRoots().contains("src/main/java"));
    assertTrue(resolution.unresolvedDependencies().contains("invalid.example:missing:1.0"));
  }

  private static JavaProjectSnapshot project(String commit, String valueType) {
    Map<String, String> source = Map.of(
        "src/main/java/demo/Store.java", """
            package demo;
            public class Store {
              public %s save(%s value) { return value; }
            }
            """.formatted(valueType, valueType),
        "src/main/java/demo/Client.java", """
            package demo;
            public class Client {
              public Object run(Store store) { return store.save("x"); }
            }
            """);
    Map<String, JavaSourceSnapshot> snapshots = new LinkedHashMap<>();
    source.forEach((path, text) -> snapshots.put(path,
        new JavaSourceSnapshot("test", commit, Integer.toHexString(text.hashCode()), path, text)));
    return new JavaProjectSnapshot("test", commit, snapshots);
  }
}
