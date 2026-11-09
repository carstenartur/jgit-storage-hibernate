/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationCoreIdentityProfilerTest {
  @TempDir Path root;
  public static class IdentityFixture {}

  @Test void acceptsOnlyTheExactDeclaredArgumentsAndGeneratedJmhCompilerOptions() {
    List<String> declared = List.of("-Xms256m", "-Xmx1024m", "-Dconnection-file=/tmp/private");
    List<String> actual = new ArrayList<>(declared);
    actual.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
        "-DcompilerBlackholesEnabled=true", "-XX:CompileCommandFile=/tmp/jmh-hints"));
    PublicationCoreIdentityProfiler.verifyJvmArguments(declared, actual, 1024L * 1024 * 1024);
    assertThrows(IllegalStateException.class,
        () -> PublicationCoreIdentityProfiler.verifyJvmArguments(declared, actual, 2L * 1024 * 1024 * 1024));
    for (String extra : List.of("-Xmx2048m", "-javaagent:/tmp/hidden.jar",
        "-XX:+UnlockDiagnosticVMOptions", "-XX:CompileCommandFile=/tmp/other-hints")) {
      List<String> changed = new ArrayList<>(actual);
      changed.add(extra);
      assertThrows(IllegalStateException.class,
          () -> PublicationCoreIdentityProfiler.verifyJvmArguments(declared, changed, 1024L * 1024 * 1024));
    }
  }

  @Test void rejectsMissingReorderedOrChangedGeneratedJmhOptions() {
    List<String> declared = List.of("-Xms256m", "-Xmx1024m");
    for (List<String> generated : List.of(
        List.of("-XX:CompileCommandFile=/tmp/jmh-hints"),
        List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UnlockDiagnosticVMOptions",
            "-DcompilerBlackholesEnabled=true", "-XX:CompileCommandFile=/tmp/jmh-hints"),
        List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
            "-DcompilerBlackholesEnabled=false", "-XX:CompileCommandFile=/tmp/jmh-hints"),
        List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
            "-DcompilerBlackholesEnabled=true", "-XX:CompileCommandFile=relative-hints"))) {
      List<String> changed = new ArrayList<>(declared);
      changed.addAll(generated);
      assertThrows(IllegalStateException.class,
          () -> PublicationCoreIdentityProfiler.verifyJvmArguments(declared, changed, 1024L * 1024 * 1024));
    }
  }

  @Test void provesTheActualLoadedJarClassAndClasspath() throws Exception {
    Path jar = fixtureJar("core.jar");
    try (URLClassLoader loader = loader(jar)) {
      Class<?> loaded = Class.forName(IdentityFixture.class.getName(), false, loader);
      JsonObject actual = PublicationCoreIdentityProfiler.verify(expected(jar), loaded);
      assertEquals(jar.toString(), actual.get("codeSource").getAsString());
      assertEquals(1, actual.getAsJsonArray("resourceUrls").size());
      assertEquals(PublicationVariantClasspath.sha256(jar), actual.get("jarSha256").getAsString());
    }
  }

  @Test void wrongJarClassHashOrClasspathCannotClaimTheSelectedVariant() throws Exception {
    Path jar = fixtureJar("core.jar");
    try (URLClassLoader loader = loader(jar)) {
      Class<?> loaded = Class.forName(IdentityFixture.class.getName(), false, loader);
      for (String key : new String[] {"jarSha256", "classSha256", "classpath"}) {
        JsonObject wrong = expected(jar);
        wrong.addProperty(key, "wrong");
        assertThrows(IllegalStateException.class, () -> PublicationCoreIdentityProfiler.verify(wrong, loaded), key);
      }
      JsonObject wrongSource = expected(fixtureJar("other.jar"));
      assertThrows(IllegalStateException.class, () -> PublicationCoreIdentityProfiler.verify(wrongSource, loaded));
    }
  }

  @Test void shadowCoreResourceIsRejectedEvenWhenTheFirstJarMatches() throws Exception {
    Path first = fixtureJar("first.jar");
    Path second = fixtureJar("shadow.jar");
    try (URLClassLoader loader = loader(first, second)) {
      Class<?> loaded = Class.forName(IdentityFixture.class.getName(), false, loader);
      assertThrows(IllegalStateException.class,
          () -> PublicationCoreIdentityProfiler.verify(expected(first), loaded));
    }
  }

  private JsonObject expected(Path jar) throws Exception {
    JsonObject expected = new JsonObject();
    expected.addProperty("jarPath", jar.toString());
    expected.addProperty("jarSha256", PublicationVariantClasspath.sha256(jar));
    expected.addProperty("classResource", resource());
    try (var input = IdentityFixture.class.getResourceAsStream("/" + resource())) {
      expected.addProperty("classSha256", PublicationVariantClasspath.sha256(input.readAllBytes()));
    }
    expected.addProperty("classpath", System.getProperty("java.class.path"));
    expected.addProperty("javaVersion", System.getProperty("java.version"));
    expected.addProperty("javaVmVersion", System.getProperty("java.vm.version"));
    return expected;
  }

  private Path fixtureJar(String filename) throws Exception {
    Path jar = root.resolve(filename);
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
        var input = IdentityFixture.class.getResourceAsStream("/" + resource())) {
      output.putNextEntry(new JarEntry(resource()));
      output.write(input.readAllBytes());
      output.closeEntry();
    }
    return jar.toRealPath();
  }

  private static URLClassLoader loader(Path... jars) throws Exception {
    URL[] urls = new URL[jars.length];
    for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
    return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
  }

  private static String resource() { return IdentityFixture.class.getName().replace('.', '/') + ".class"; }
}
