/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.junit.jupiter.api.Test;

/** Records whether the selected JGit version exposes a persisted DFS Multi-Pack-Index extension. */
class JGitDfsMidxCapabilityTest {

  @Test
  void writesMachineReadableCapabilityEvidence() throws Exception {
    String implementationVersion =
        Optional.ofNullable(PackExt.class.getPackage().getImplementationVersion())
            .orElse("unknown");
    String[] extensions =
        Arrays.stream(PackExt.values()).map(Enum::name).sorted().toArray(String[]::new);
    boolean supported = Arrays.stream(extensions).anyMatch(JGitDfsMidxCapabilityTest::isMidxName);

    Path output = Path.of("target", "jgit-dfs-midx-capability.json");
    Files.createDirectories(output.getParent());
    Files.writeString(
        output,
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"jgitImplementationVersion\": \""
            + escape(implementationVersion)
            + "\",\n"
            + "  \"persistedDfsMidxExtensionAvailable\": "
            + supported
            + ",\n"
            + "  \"packExtensions\": ["
            + Arrays.stream(extensions)
                .map(name -> "\"" + escape(name) + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElse("")
            + "]\n"
            + "}\n");

    assertTrue(Files.isRegularFile(output));
    assertTrue(Files.size(output) > 2);
  }

  private static boolean isMidxName(String value) {
    String normalized = value.toLowerCase(Locale.ROOT).replace("_", "");
    return normalized.contains("midx")
        || normalized.contains("multipackindex")
        || (normalized.contains("multi")
            && normalized.contains("pack")
            && normalized.contains("index"));
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
