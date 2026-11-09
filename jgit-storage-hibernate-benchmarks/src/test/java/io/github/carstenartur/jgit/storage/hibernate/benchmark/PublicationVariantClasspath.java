/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Test-only, serial JMH fork classpath selection with no production loading hook. */
final class PublicationVariantClasspath {
  static final String CORE_RESOURCE =
      "io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.class";

  private PublicationVariantClasspath() {}

  static String current() {
    return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
  }

  static String replace(String classpath, Path replacement) throws IOException {
    Path selected = replacement.toRealPath();
    if (!Files.isRegularFile(selected) || !containsCore(selected)) {
      throw new IllegalArgumentException("Selected core JAR lacks the core class");
    }
    List<String> paths = new ArrayList<>();
    HashSet<Path> unique = new HashSet<>();
    int coreEntries = 0;
    for (String item : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
      if (item.isBlank() || item.contains("*")) {
        throw new IllegalArgumentException("Child classpath must be explicit and expanded");
      }
      Path path = Path.of(item).toRealPath();
      if (!unique.add(path)) throw new IllegalArgumentException("Duplicate classpath entry");
      if (containsCore(path)) {
        coreEntries++;
        paths.add(selected.toString());
      } else {
        paths.add(path.toString());
      }
    }
    if (coreEntries != 1) throw new IllegalArgumentException("Expected exactly one core classpath entry");
    return String.join(File.pathSeparator, paths);
  }

  private static boolean containsCore(Path path) throws IOException {
    if (Files.isDirectory(path)) return Files.isRegularFile(path.resolve(CORE_RESOURCE));
    try (JarFile jar = new JarFile(path.toFile())) {
      if (jar.getManifest() != null) {
        String inherited = jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        if (inherited != null && !inherited.isBlank()) {
          throw new IllegalArgumentException("Child classpath contains transitive manifest entries");
        }
      }
      return jar.getJarEntry(CORE_RESOURCE) != null;
    }
  }

  static <T> T with(String selected, CheckedSupplier<T> action) throws Exception {
    String previous = System.getProperty("java.class.path");
    System.setProperty("java.class.path", selected);
    try {
      return action.get();
    } finally {
      if (previous == null) System.clearProperty("java.class.path");
      else System.setProperty("java.class.path", previous);
    }
  }

  static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @FunctionalInterface interface CheckedSupplier<T> {
    T get() throws Exception;
  }
}
