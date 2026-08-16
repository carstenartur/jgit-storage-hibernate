/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.junit.jupiter.api.Test;

class SmartHttpRepositoryNameMapperTest {

  private final SmartHttpRepositoryNameMapper mapper = SmartHttpRepositoryNameMapper.strict();

  @Test
  void canonicalizesOneGitSuffixAndKeepsLogicalNamespaces() throws Exception {
    assertEquals("team/demo", mapper.map("team/demo.git").value());
    assertEquals("team/demo.git", mapper.map("team/demo.git.git").value());
    assertEquals("geschäft/änderungen", mapper.map("geschäft/änderungen").value());
  }

  @Test
  void rejectsAmbiguousTraversalAndOutOfSchemaNames() {
    List<String> invalid =
        List.of(
            "",
            " ",
            " demo",
            "demo ",
            "/demo",
            "demo/",
            "demo//topic",
            ".",
            "..",
            "team/../demo",
            "team/./demo",
            "team\\demo",
            "demo\nother",
            ".git",
            "x".repeat(256));
    for (String candidate : invalid) {
      assertThrows(RepositoryNotFoundException.class, () -> mapper.map(candidate), candidate);
    }
    assertThrows(RepositoryNotFoundException.class, () -> mapper.map(null));
  }
}
