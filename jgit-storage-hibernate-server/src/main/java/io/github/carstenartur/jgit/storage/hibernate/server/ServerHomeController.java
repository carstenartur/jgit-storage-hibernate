/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated discovery document for the runnable server. */
@RestController
public class ServerHomeController {

  @GetMapping("/")
  public Map<String, Object> home() {
    return Map.of(
        "service", "jgit-storage-hibernate-server",
        "git", "/git/{repository}.git",
        "repositories", "/api/repositories",
        "health", "/actuator/health/readiness",
        "promise", "Push with normal Git. Store it transactionally in PostgreSQL. Query who changed what, where and when.");
  }
}
