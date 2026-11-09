/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationVariantClasspathTest {
  @TempDir Path root;

  @Test void replacesExactlyOneCoreEntryAndPreservesDependencyOrder() throws Exception {
    Path before = jar("before.jar", Map.of("before.class", "before"), null);
    Path original = jar("original.jar", Map.of(PublicationVariantClasspath.CORE_RESOURCE, "old"), null);
    Path after = jar("after.jar", Map.of("after.class", "after"), null);
    Path replacement = jar("replacement.jar", Map.of(PublicationVariantClasspath.CORE_RESOURCE, "new"), null);
    String actual = PublicationVariantClasspath.replace(join(before, original, after), replacement);
    assertEquals(join(before, replacement, after), actual);
  }

  @Test void missingOrDuplicateCoreDefinitionsFailBeforeLaunch() throws Exception {
    Path plain = jar("plain.jar", Map.of("Other.class", "x"), null);
    Path one = jar("one.jar", Map.of(PublicationVariantClasspath.CORE_RESOURCE, "one"), null);
    Path two = jar("two.jar", Map.of(PublicationVariantClasspath.CORE_RESOURCE, "two"), null);
    assertThrows(IllegalArgumentException.class, () -> PublicationVariantClasspath.replace(join(plain), one));
    assertThrows(IllegalArgumentException.class, () -> PublicationVariantClasspath.replace(join(one, two), two));
  }

  @Test void manifestAndWildcardClasspathAmbiguityIsRejected() throws Exception {
    Path core = jar("core.jar", Map.of(PublicationVariantClasspath.CORE_RESOURCE, "core"), null);
    Path booter = jar("booter.jar", Map.of("Boot.class", "boot"), "core.jar");
    assertThrows(IllegalArgumentException.class,
        () -> PublicationVariantClasspath.replace(join(booter, core), core));
    assertThrows(IllegalArgumentException.class,
        () -> PublicationVariantClasspath.replace(root.resolve("*").toString(), core));
  }

  @Test void usesSurefireExpandedClasspathAndRestoresPropertyAfterFailure() throws Exception {
    String oldExpanded = System.getProperty("surefire.test.class.path");
    String oldRaw = System.getProperty("java.class.path");
    try {
      System.setProperty("surefire.test.class.path", "expanded-test-classpath");
      System.setProperty("java.class.path", "booter-only.jar");
      assertEquals("expanded-test-classpath", PublicationVariantClasspath.current());
      assertThrows(IOException.class, () -> PublicationVariantClasspath.with("selected-core", () -> {
        assertEquals("selected-core", System.getProperty("java.class.path"));
        throw new IOException("launch failed");
      }));
      assertEquals("booter-only.jar", System.getProperty("java.class.path"));
      System.clearProperty("surefire.test.class.path");
      assertEquals("booter-only.jar", PublicationVariantClasspath.current());
    } finally {
      restore("surefire.test.class.path", oldExpanded);
      restore("java.class.path", oldRaw);
    }
  }

  private Path jar(String name, Map<String, String> content, String classpath) throws IOException {
    Path path = root.resolve(name);
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (classpath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
      for (var item : content.entrySet()) {
        out.putNextEntry(new JarEntry(item.getKey()));
        out.write(item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return path.toRealPath();
  }

  private static String join(Path... paths) {
    return java.util.Arrays.stream(paths).map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }
}
