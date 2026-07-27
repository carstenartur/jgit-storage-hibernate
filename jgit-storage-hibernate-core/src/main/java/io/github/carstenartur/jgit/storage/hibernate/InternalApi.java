/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks implementation packages that are technically public for cross-package integration but are
 * not part of the supported consumer API.
 *
 * <p>Types in an annotated package may change when the pinned JGit implementation changes. Consumers
 * should use {@link HibernateGitStorage}, {@link HibernateRepositoryFactory} and other unmarked
 * module-owned facades instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
public @interface InternalApi {}
