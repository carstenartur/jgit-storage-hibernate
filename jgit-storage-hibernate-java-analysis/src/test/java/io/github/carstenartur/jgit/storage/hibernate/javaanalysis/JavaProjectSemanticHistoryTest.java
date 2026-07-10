/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaProjectSemanticHistoryTest {

  @Test
  void resolvesReferencesAcrossCompilationUnitsAndDetectsSignatureChange() {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaProjectAnalysisResult before = analyzer.analyze(
        project("a", "public String save(String value)"),
        JavaAnalysisConfiguration.java21BindingAware());
    JavaProjectAnalysisResult after = analyzer.analyze(
        project("b", "public CharSequence save(CharSequence value)"),
        JavaAnalysisConfiguration.java21BindingAware());

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

  @Test
  void doesNotLeakSiblingModulePropertiesAcrossPomFiles(@TempDir Path localRepository) throws IOException {
    Path dependency = localRepository.resolve("com/example/shared/1.0/shared-1.0.jar");
    Files.createDirectories(dependency.getParent());
    Files.writeString(dependency, "stub");

    var resolution = new MavenJavaAnalysisConfigurationResolver(localRepository).resolve(Map.of(
        "pom.xml", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <properties>
                <dep.version>1.0</dep.version>
                <maven.compiler.release>17</maven.compiler.release>
              </properties>
              <modules>
                <module>a</module>
                <module>b</module>
              </modules>
            </project>
            """,
        "a/pom.xml", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <properties>
                <dep.version>2.0</dep.version>
                <maven.compiler.release>11</maven.compiler.release>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>shared</artifactId>
                  <version>${dep.version}</version>
                </dependency>
              </dependencies>
            </project>
            """,
        "b/pom.xml", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>shared</artifactId>
                  <version>${dep.version}</version>
                </dependency>
              </dependencies>
            </project>
            """));

    assertEquals("17", resolution.configuration().sourceLevel());
    assertTrue(resolution.configuration().classpathEntries().contains(dependency.toAbsolutePath().toString()));
    assertEquals(1, resolution.unresolvedDependencies().size());
    assertTrue(resolution.unresolvedDependencies().contains("com.example:shared:2.0"));
  }

  @Test
  void semanticDiffReportsModifierChangesWithoutMislabelingThemAsVisibility() {
    JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer();
    JavaProjectAnalysisResult before = analyzer.analyze(
        project("before", "public String save(String value)"),
        JavaAnalysisConfiguration.java21BindingAware());
    JavaProjectAnalysisResult after = analyzer.analyze(
        project("after", "public final String save(String value)"),
        JavaAnalysisConfiguration.java21BindingAware());

    assertTrue(new JavaSemanticDiff().compare(before, after).stream()
        .anyMatch(change -> change.kind() == SemanticChangeKind.MODIFIERS_CHANGED));
  }

  @Test
  void semanticHistoryQueryRejectsNullLookupArguments() {
    JavaProjectAnalysisResult result = new JavaProjectAnalyzer().analyze(
        project("a", "public String save(String value)"),
        JavaAnalysisConfiguration.java21BindingAware());
    SemanticHistoryQuery query = new SemanticHistoryQuery(result);

    assertEquals("simpleName",
        assertThrows(NullPointerException.class, () -> query.symbolsNamed(null)).getMessage());
    assertEquals("qualifiedTypeName",
        assertThrows(NullPointerException.class, () -> query.methodsReturning(null)).getMessage());
    assertEquals("stableSemanticKey",
        assertThrows(NullPointerException.class, () -> query.referencesToSemanticKey(null)).getMessage());
    assertEquals("methodName",
        assertThrows(NullPointerException.class, () -> query.methodInvocationsNamed(null)).getMessage());
  }

  private static JavaProjectSnapshot project(String commit, String saveMethod) {
    Map<String, String> source = Map.of(
        "src/main/java/demo/Store.java", """
            package demo;
            public class Store {
              %s { return value; }
            }
            """.formatted(saveMethod),
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
