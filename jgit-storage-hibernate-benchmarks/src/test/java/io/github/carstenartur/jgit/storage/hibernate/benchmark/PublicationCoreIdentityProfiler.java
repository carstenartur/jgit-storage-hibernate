/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.carstenartur.jgit.storage.hibernate.objects.ReadAheadHibernateObjDatabase;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.runner.IterationType;

/** Records the actually loaded core implementation once, before benchmark warmup. */
public final class PublicationCoreIdentityProfiler implements InternalProfiler {
  private static final List<String> JMH_COMPILER_BLACKHOLE_ARGUMENTS = List.of(
      "-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
      "-DcompilerBlackholesEnabled=true");
  private final JsonObject expected;
  private boolean checked;

  public PublicationCoreIdentityProfiler(String initLine) {
    expected = JsonParser.parseString(new String(Base64.getDecoder().decode(initLine),
        StandardCharsets.UTF_8)).getAsJsonObject();
  }

  @Override public String getDescription() { return "Loaded core JAR/class identity before warmup"; }

  @Override public void beforeIteration(BenchmarkParams benchmark, IterationParams iteration) {
    if (checked) return;
    try {
      require(iteration.getType() == IterationType.WARMUP, "Identity must be checked before measurement");
      require(System.getProperty("sun.java.command", "").startsWith("org.openjdk.jmh.runner.ForkedMain "),
          "Identity check must run inside the actual JMH child");
      List<String> inputs = ManagementFactory.getRuntimeMXBean().getInputArguments();
      List<String> declaredArguments = new ArrayList<>();
      expected.getAsJsonArray("jvmArgs").forEach(value -> declaredArguments.add(value.getAsString()));
      verifyJvmArguments(declaredArguments, inputs, Runtime.getRuntime().maxMemory());
      JsonObject actual = verify(expected, ReadAheadHibernateObjDatabase.class);
      actual.addProperty("iterationType", iteration.getType().name());
      actual.addProperty("command", System.getProperty("sun.java.command"));
      actual.addProperty("recordedAt", Instant.now().toString());
      actual.addProperty("pid", ProcessHandle.current().pid());
      actual.addProperty("maxHeapBytes", Runtime.getRuntime().maxMemory());
      JsonArray arguments = new JsonArray();
      inputs.forEach(arguments::add);
      actual.add("actualJvmArgs", arguments);
      Files.writeString(Path.of(expected.get("output").getAsString()),
          new GsonBuilder().setPrettyPrinting().create().toJson(actual) + "\n", StandardOpenOption.CREATE_NEW);
      checked = true;
    } catch (Exception failure) {
      try {
        JsonObject record = new JsonObject();
        record.addProperty("failureType", failure.getClass().getName());
        record.addProperty("message", failure.getMessage());
        Files.writeString(Path.of(expected.get("failureOutput").getAsString()), record + "\n",
            StandardOpenOption.CREATE_NEW);
      } catch (Exception recordingFailure) {
        failure.addSuppressed(recordingFailure);
      }
      throw new IllegalStateException("Actual child core identity could not be established", failure);
    }
  }

  static void verifyJvmArguments(List<String> declared, List<String> actual, long maxHeapBytes) {
    List<String> prefix = new ArrayList<>(declared);
    prefix.addAll(JMH_COMPILER_BLACKHOLE_ARGUMENTS);
    require(maxHeapBytes == 1024L * 1024 * 1024 && actual.size() == prefix.size() + 1
        && actual.subList(0, prefix.size()).equals(prefix)
        && actual.getLast().matches("-XX:CompileCommandFile=/.+"),
        "Actual child JVM/heap arguments differ from the fixed declaration and JMH compiler mode");
  }

  static JsonObject verify(JsonObject expected, Class<?> coreClass) throws Exception {
    String resource = coreClass.getName().replace('.', '/') + ".class";
    require(resource.equals(expected.get("classResource").getAsString()), "Unexpected core class");
    Path selected = Path.of(expected.get("jarPath").getAsString()).toRealPath();
    Path actualSource = Path.of(coreClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    require(Files.isRegularFile(selected) && selected.equals(actualSource), "Actual CodeSource differs from selected core JAR");
    var resources = Collections.list(coreClass.getClassLoader().getResources(resource));
    require(resources.size() == 1, "Core resource is missing or shadowed");
    String jarHash = PublicationVariantClasspath.sha256(actualSource);
    String classHash;
    try (var input = coreClass.getResourceAsStream("/" + resource)) {
      require(input != null, "Loaded core class bytes are unavailable");
      classHash = PublicationVariantClasspath.sha256(input.readAllBytes());
    }
    require(jarHash.equals(expected.get("jarSha256").getAsString()), "Actual core JAR digest differs");
    require(classHash.equals(expected.get("classSha256").getAsString()), "Actual core class digest differs");
    require(System.getProperty("java.class.path").equals(expected.get("classpath").getAsString()),
        "Actual child classpath differs");
    require(System.getProperty("java.version").equals(expected.get("javaVersion").getAsString())
        && System.getProperty("java.vm.version").equals(expected.get("javaVmVersion").getAsString()),
        "Actual child JVM version differs");
    JsonObject actual = expected.deepCopy();
    actual.addProperty("schema", "jgit-publication-core-identity/v1");
    actual.addProperty("codeSource", actualSource.toString());
    actual.addProperty("jarSha256", jarHash);
    actual.addProperty("classSha256", classHash);
    JsonArray locations = new JsonArray();
    resources.forEach(url -> locations.add(url.toString()));
    actual.add("resourceUrls", locations);
    return actual;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  @Override public Collection<? extends Result<?>> afterIteration(
      BenchmarkParams benchmark, IterationParams iteration, IterationResult result) {
    return List.of();
  }
}
