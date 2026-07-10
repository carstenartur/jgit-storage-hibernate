/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaAnalysisRun;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaProjectionState;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.List;

/** Entity registration helper for consumers creating Hibernate session factories manually. */
public final class JavaAnalysisEntities {

  private JavaAnalysisEntities() {}

  public static List<Class<?>> annotatedClasses() {
    return List.of(
        JavaAnalysisRun.class,
        JavaProjectionState.class,
        JavaSymbolIndex.class,
        JavaReferenceIndex.class);
  }
}
