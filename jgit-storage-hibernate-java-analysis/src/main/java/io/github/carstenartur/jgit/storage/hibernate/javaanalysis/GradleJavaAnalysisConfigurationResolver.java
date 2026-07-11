/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight Gradle build resolver for commit snapshots. */
public final class GradleJavaAnalysisConfigurationResolver {

  private static final Pattern STRING_SOURCE_COMPATIBILITY = Pattern.compile(
      "sourceCompatibility\\s*=\\s*['\"]([0-9]+(?:\\.[0-9]+)?)['\"]");
  private static final Pattern ENUM_SOURCE_COMPATIBILITY = Pattern.compile(
      "sourceCompatibility\\s*=\\s*JavaVersion\\.VERSION_([0-9_]+)");
  private static final Pattern IMPLEMENTATION_DEPENDENCY = Pattern.compile(
      "implementation\\s*(?:\\(\\s*)?['\"]([^'\":]+:[^'\":]+:[^'\"]+)['\"]\\s*\\)?");

  public record Resolution(
      JavaAnalysisConfiguration configuration,
      List<String> sourceRoots,
      List<String> modules,
      List<String> unresolvedDependencies) {
    public Resolution {
      configuration = Objects.requireNonNull(configuration, "configuration");
      sourceRoots = List.copyOf(sourceRoots);
      modules = List.copyOf(modules);
      unresolvedDependencies = List.copyOf(unresolvedDependencies);
    }
  }

  private final Path localRepository;

  public GradleJavaAnalysisConfigurationResolver() {
    this(Path.of(System.getProperty("user.home"), ".m2", "repository"));
  }

  public GradleJavaAnalysisConfigurationResolver(Path localRepository) {
    this.localRepository = Objects.requireNonNull(localRepository, "localRepository");
  }

  public Resolution resolve(Map<String, String> repositoryFiles) {
    Objects.requireNonNull(repositoryFiles, "repositoryFiles");
    Set<String> sourceRoots = new LinkedHashSet<>();
    Set<String> modules = new LinkedHashSet<>();
    Set<String> classpath = new LinkedHashSet<>();
    List<String> unresolved = new ArrayList<>();
    String sourceLevel = null;

    List<String> buildFiles = repositoryFiles.keySet().stream()
        .filter(GradleJavaAnalysisConfigurationResolver::isGradleBuildFile)
        .sorted()
        .toList();
    for (String buildFile : buildFiles) {
      String module = moduleDirectory(buildFile);
      modules.add(module);
      sourceRoots.add(normalize(module.isEmpty() ? "src/main/java" : module + "/src/main/java"));
      String script = repositoryFiles.get(buildFile);
      sourceLevel = preferSourceLevel(sourceLevel, findSourceLevel(script));
      resolveDependencies(script, classpath, unresolved);
    }

    JavaAnalysisConfiguration configuration = new JavaAnalysisConfiguration(
        sourceLevel == null ? "21" : sourceLevel,
        BindingMode.RECOVERY,
        List.copyOf(classpath),
        List.copyOf(sourceRoots),
        encodings(sourceRoots.size()),
        List.of(),
        true,
        JavaAnalysisConfiguration.DEFAULT_ANALYZER_VERSION);
    return new Resolution(configuration, List.copyOf(sourceRoots), List.copyOf(modules), unresolved);
  }

  private static List<String> encodings(int count) {
    List<String> encodings = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      encodings.add(java.nio.charset.StandardCharsets.UTF_8.name());
    }
    return List.copyOf(encodings);
  }

  private void resolveDependencies(
      String script, Set<String> classpath, List<String> unresolvedDependencies) {
    Matcher matcher = IMPLEMENTATION_DEPENDENCY.matcher(script);
    while (matcher.find()) {
      String coordinate = matcher.group(1).trim();
      String[] parts = coordinate.split(":", 3);
      if (parts.length != 3) {
        unresolvedDependencies.add(coordinate);
        continue;
      }
      Path jar = localRepository.resolve(parts[0].replace('.', '/'))
          .resolve(parts[1])
          .resolve(parts[2])
          .resolve(parts[1] + "-" + parts[2] + ".jar");
      if (Files.isRegularFile(jar)) {
        classpath.add(jar.toAbsolutePath().toString());
      } else {
        unresolvedDependencies.add(coordinate);
      }
    }
  }

  private static String findSourceLevel(String script) {
    Matcher stringMatcher = STRING_SOURCE_COMPATIBILITY.matcher(script);
    if (stringMatcher.find()) {
      return normalizeSourceLevel(stringMatcher.group(1));
    }
    Matcher enumMatcher = ENUM_SOURCE_COMPATIBILITY.matcher(script);
    if (enumMatcher.find()) {
      return normalizeSourceLevel(enumMatcher.group(1).replace('_', '.'));
    }
    return null;
  }

  private static boolean isGradleBuildFile(String path) {
    return path.equals("build.gradle")
        || path.equals("build.gradle.kts")
        || path.endsWith("/build.gradle")
        || path.endsWith("/build.gradle.kts");
  }

  private static String moduleDirectory(String buildFile) {
    int slash = buildFile.lastIndexOf('/');
    return slash < 0 ? "" : normalize(buildFile.substring(0, slash));
  }

  private static String normalizeSourceLevel(String sourceLevel) {
    if (sourceLevel == null || sourceLevel.isBlank()) {
      return sourceLevel;
    }
    if (sourceLevel.startsWith("1.")) {
      return sourceLevel.substring(2);
    }
    return sourceLevel;
  }

  private static String preferSourceLevel(String current, String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return current;
    }
    if (current == null || current.isBlank()) {
      return candidate;
    }
    Integer currentRelease = featureRelease(current);
    Integer candidateRelease = featureRelease(candidate);
    if (currentRelease != null && candidateRelease != null) {
      return candidateRelease > currentRelease ? candidate : current;
    }
    return candidate.compareTo(current) > 0 ? candidate : current;
  }

  private static Integer featureRelease(String sourceLevel) {
    try {
      return Integer.parseInt(normalizeSourceLevel(sourceLevel));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String normalize(String path) {
    String normalized = path.replace('\\', '/');
    while (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
