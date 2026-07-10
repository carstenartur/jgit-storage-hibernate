/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Map;
import java.util.Objects;

/** Entry point for commit-scoped Java project analysis. */
public record ProjectAnalysisRequest(
    String repositoryName,
    String commitId,
    Map<String, String> repositoryFiles,
    BuildSystem buildSystem) {

  public enum BuildSystem {
    MAVEN,
    GRADLE,
    UNKNOWN
  }

  public ProjectAnalysisRequest {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commitId, "commitId");
    repositoryFiles = Map.copyOf(Objects.requireNonNull(repositoryFiles, "repositoryFiles"));
    buildSystem = Objects.requireNonNull(buildSystem, "buildSystem");
  }

  public static ProjectAnalysisRequest from(
      String repositoryName,
      String commitId,
      Map<String, String> repositoryFiles) {
    Objects.requireNonNull(repositoryFiles, "repositoryFiles");
    return new ProjectAnalysisRequest(
        repositoryName,
        commitId,
        repositoryFiles,
        detectBuildSystem(repositoryFiles));
  }

  private static BuildSystem detectBuildSystem(Map<String, String> repositoryFiles) {
    boolean hasPom = repositoryFiles.containsKey("pom.xml")
        || repositoryFiles.keySet().stream().anyMatch(path -> path.endsWith("/pom.xml"));
    if (hasPom) {
      return BuildSystem.MAVEN;
    }
    boolean hasGradle = repositoryFiles.keySet().stream().anyMatch(path -> path.equals("build.gradle")
        || path.equals("build.gradle.kts")
        || path.endsWith("/build.gradle")
        || path.endsWith("/build.gradle.kts"));
    return hasGradle ? BuildSystem.GRADLE : BuildSystem.UNKNOWN;
  }
}
