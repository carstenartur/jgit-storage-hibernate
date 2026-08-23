/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Executable database-backed JGit Smart HTTP service. */
@SpringBootApplication
public class JgitStorageHibernateServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(JgitStorageHibernateServerApplication.class, args);
  }
}
