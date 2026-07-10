/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Analyzes all Java sources of a commit with one shared source path.
 *
 * <p>The sources are materialized into a temporary source tree so JDT can resolve declarations in
 * sibling compilation units without an Eclipse workspace. External libraries remain controlled by
 * {@link JavaAnalysisConfiguration#classpathEntries()}.
 */
public final class JavaProjectAnalyzer {

  private final JavaJdtAnalyzer fileAnalyzer;

  public JavaProjectAnalyzer() {
    this(new JavaJdtAnalyzer());
  }

  JavaProjectAnalyzer(JavaJdtAnalyzer fileAnalyzer) {
    this.fileAnalyzer = Objects.requireNonNull(fileAnalyzer, "fileAnalyzer");
  }

  public JavaProjectAnalysisResult analyze(
      JavaProjectSnapshot project, JavaAnalysisConfiguration configuration) {
    Objects.requireNonNull(project, "project");
    Objects.requireNonNull(configuration, "configuration");

    try {
      Path sourceRoot = Files.createTempDirectory("jgit-hibernate-java-analysis-");
      try {
        materialize(project, sourceRoot);
        JavaAnalysisConfiguration projectConfiguration = withSourceRoot(configuration, sourceRoot);
        Map<String, JavaAnalysisResult> files = new LinkedHashMap<>();
        List<JavaSymbolIndex> symbols = new ArrayList<>();
        List<JavaReferenceIndex> references = new ArrayList<>();
        int problems = 0;
        int errors = 0;

        for (JavaSourceSnapshot source : project.sources().values().stream()
            .sorted(java.util.Comparator.comparing(JavaSourceSnapshot::path))
            .toList()) {
          JavaAnalysisResult result = fileAnalyzer.analyze(source, projectConfiguration);
          files.put(source.path(), result);
          symbols.addAll(result.symbols());
          references.addAll(result.references());
          problems += result.analysisRun().getProblemCount();
          errors += result.analysisRun().getErrorCount();
        }
        return new JavaProjectAnalysisResult(project, files, symbols, references, problems, errors);
      } finally {
        try {
          deleteRecursively(sourceRoot);
        } catch (IOException ignored) {
          // Best-effort cleanup: successful analysis should not fail because temp files cannot be deleted.
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not materialize project sources for JDT analysis", e);
    }
  }

  private static JavaAnalysisConfiguration withSourceRoot(
      JavaAnalysisConfiguration configuration, Path sourceRoot) {
    List<String> sourcepaths = new ArrayList<>();
    if (configuration.sourcepathEntries().isEmpty()) {
      sourcepaths.add(sourceRoot.toAbsolutePath().toString());
    } else {
      for (String entry : configuration.sourcepathEntries()) {
        Path path = Path.of(entry);
        Path resolved = path.isAbsolute() ? path : safeResolve(sourceRoot, entry);
        sourcepaths.add(resolved.toAbsolutePath().toString());
      }
    }
    List<String> encodings = configuration.encodings().isEmpty()
        ? repeatedUtf8(sourcepaths.size())
        : new ArrayList<>(configuration.encodings());
    return new JavaAnalysisConfiguration(
        configuration.sourceLevel(),
        configuration.bindingMode(),
        configuration.classpathEntries(),
        sourcepaths,
        encodings,
        configuration.modulepathEntries(),
        configuration.includeRunningVmBootClasspath(),
        configuration.analyzerVersion());
  }

  private static List<String> repeatedUtf8(int count) {
    List<String> encodings = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      encodings.add(StandardCharsets.UTF_8.name());
    }
    return encodings;
  }

  private static void materialize(JavaProjectSnapshot project, Path sourceRoot) throws IOException {
    for (JavaSourceSnapshot source : project.sources().values()) {
      Path target = safeResolve(sourceRoot, source.path());
      Files.createDirectories(target.getParent());
      Files.writeString(target, source.source(), StandardCharsets.UTF_8);
    }
  }

  private static Path safeResolve(Path root, String repositoryPath) {
    Path target = root.resolve(repositoryPath.replace('\\', '/')).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("Source path escapes project root: " + repositoryPath);
    }
    return target;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
