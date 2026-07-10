/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitClasspathResolutionTest {

  @Test
  void resolvesMavenMultiModuleAtCommitAndAnalyzesWithJdt() {
    Map<String, String> repositoryFiles = new LinkedHashMap<>();
    repositoryFiles.put("pom.xml", """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <properties>
            <maven.compiler.release>21</maven.compiler.release>
          </properties>
          <modules>
            <module>api</module>
            <module>app</module>
          </modules>
        </project>
        """);
    repositoryFiles.put("api/pom.xml", """
        <project><modelVersion>4.0.0</modelVersion></project>
        """);
    repositoryFiles.put("app/pom.xml", """
        <project><modelVersion>4.0.0</modelVersion></project>
        """);
    repositoryFiles.put("src/main/java/demo/api/Store.java", """
        package demo.api;
        public class Store {
          public String save(String value) { return value; }
        }
        """);
    repositoryFiles.put("src/main/java/demo/app/Client.java", """
        package demo.app;
        import demo.api.Store;
        public class Client {
          public String run(Store store) { return store.save("x"); }
        }
        """);
    MavenJavaAnalysisConfigurationResolver.Resolution resolution =
        new MavenJavaAnalysisConfigurationResolver().resolve(repositoryFiles);
    JavaProjectAnalysisResult result =
        new JavaProjectAnalyzer().analyze(
            project("repo", "c1", repositoryFiles),
            JavaAnalysisConfiguration.java21BindingAware());
    assertEquals("21", resolution.configuration().sourceLevel());
    assertTrue(resolution.sourceRoots().contains("api/src/main/java"));
    assertTrue(resolution.sourceRoots().contains("app/src/main/java"));
    assertTrue(resolution.modules().contains("api"));
    assertTrue(resolution.modules().contains("app"));
    assertFalse(result.symbols().isEmpty());
    assertTrue(result.symbols().stream().anyMatch(symbol -> symbol.getRawBindingKey() != null));
    assertTrue(result.symbols().stream().map(JavaSymbolIndex::getStableSemanticKey).anyMatch(key -> key != null && !key.isBlank()));
    assertTrue(new SemanticHistoryQuery(result).symbolsNamed("Store").stream()
        .anyMatch(symbol -> symbol.getQualifiedName().contains("Store")));
    assertTrue(new SemanticHistoryQuery(result).methodInvocationsNamed("save").stream()
        .map(JavaReferenceIndex::getTargetStableSemanticKey)
        .anyMatch(key -> key != null && !key.isBlank()));
  }

  @Test
  void missingMavenDependencyProducesExplicitDiagnostics() {
    Map<String, String> repositoryFiles = new LinkedHashMap<>();
    repositoryFiles.put("pom.xml", """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <properties><maven.compiler.release>21</maven.compiler.release></properties>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>missing</artifactId>
              <version>1.0</version>
            </dependency>
          </dependencies>
        </project>
        """);
    repositoryFiles.put("src/main/java/demo/BrokenClient.java", """
        package demo;
        import com.example.missing.ExternalType;
        public class BrokenClient {
          public ExternalType echo(ExternalType value) { return value; }
        }
        """);

    MavenJavaAnalysisConfigurationResolver.Resolution resolution =
        new MavenJavaAnalysisConfigurationResolver().resolve(repositoryFiles);
    JavaProjectAnalysisResult result = new JavaProjectAnalyzer().analyze(project("repo", "c2", repositoryFiles), resolution.configuration());

    assertTrue(resolution.unresolvedDependencies().contains("com.example:missing:1.0"));
    assertFalse(result.files().isEmpty());
    assertNotNull(result.files().get("src/main/java/demo/BrokenClient.java"));
    assertTrue(result.problemCount() > 0);
    assertTrue(result.files().get("src/main/java/demo/BrokenClient.java").analysisRun().getErrorCount() > 0);
    assertTrue(result.symbols().stream().anyMatch(symbol -> symbol.getBindingStatus() != BindingStatus.NONE));
    assertFalse(result.references().isEmpty());
    assertTrue(result.references().stream().allMatch(reference -> reference.getBindingStatus() != BindingStatus.NONE));
  }

  @Test
  void detectsGradleProjectAndInfersSourceRoots(@TempDir Path localRepository) throws IOException {
    createEmptyJar(localRepository, "com.example", "present", "1.0");
    Map<String, String> repositoryFiles = new LinkedHashMap<>();
    repositoryFiles.put("build.gradle", """
        plugins { id 'java' }
        java { sourceCompatibility = JavaVersion.VERSION_21 }
        dependencies {
          implementation("com.example:present:1.0")
          implementation("com.example:missing:9.9")
        }
        """);
    repositoryFiles.put("module-a/build.gradle.kts", """
        plugins { java }
        java { sourceCompatibility = "17" }
        """);

    GradleJavaAnalysisConfigurationResolver.Resolution resolution =
        new GradleJavaAnalysisConfigurationResolver(localRepository).resolve(repositoryFiles);

    assertEquals("21", resolution.configuration().sourceLevel());
    assertTrue(resolution.modules().contains(""));
    assertTrue(resolution.modules().contains("module-a"));
    assertTrue(resolution.sourceRoots().contains("src/main/java"));
    assertTrue(resolution.sourceRoots().contains("module-a/src/main/java"));
    assertTrue(resolution.configuration().classpathEntries().stream().anyMatch(path -> path.endsWith("present-1.0.jar")));
    assertTrue(resolution.unresolvedDependencies().contains("com.example:missing:9.9"));
  }

  @Test
  void projectAnalysisRequestDetectsBuildSystem() {
    assertEquals(ProjectAnalysisRequest.BuildSystem.MAVEN,
        ProjectAnalysisRequest.from("repo", "m1", Map.of("pom.xml", "<project/>"))
            .buildSystem());
    assertEquals(ProjectAnalysisRequest.BuildSystem.GRADLE,
        ProjectAnalysisRequest.from("repo", "g1", Map.of("build.gradle", "plugins { id 'java' }"))
            .buildSystem());
    assertEquals(ProjectAnalysisRequest.BuildSystem.UNKNOWN,
        ProjectAnalysisRequest.from("repo", "u1", Map.of("README.md", "no build"))
            .buildSystem());
  }

  private static JavaProjectSnapshot project(
      String repositoryName, String commitId, Map<String, String> repositoryFiles) {
    Map<String, JavaSourceSnapshot> sources = new LinkedHashMap<>();
    repositoryFiles.forEach((path, source) -> {
      if (path.endsWith(".java")) {
        sources.put(path,
            new JavaSourceSnapshot(repositoryName, commitId, Integer.toHexString(source.hashCode()), path, source));
      }
    });
    return new JavaProjectSnapshot(repositoryName, commitId, sources);
  }

  private static void createEmptyJar(Path localRepository, String groupId, String artifactId, String version)
      throws IOException {
    Path jar = localRepository.resolve(groupId.replace('.', '/'))
        .resolve(artifactId)
        .resolve(version)
        .resolve(artifactId + "-" + version + ".jar");
    Files.createDirectories(jar.getParent());
    try (OutputStream output = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(output)) {
      // empty jar is sufficient for resolver tests
    }
  }
}
