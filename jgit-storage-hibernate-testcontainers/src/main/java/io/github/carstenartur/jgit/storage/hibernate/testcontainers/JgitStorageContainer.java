/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Typed container for the same OCI image used by standalone deployments. */
public class JgitStorageContainer extends GenericContainer<JgitStorageContainer> {

  public static final String DEFAULT_IMAGE_VERSION = "0.11.2";
  public static final String DEFAULT_IMAGE =
      "ghcr.io/carstenartur/jgit-storage-hibernate-server:" + DEFAULT_IMAGE_VERSION;
  public static final int HTTP_PORT = 8080;

  private String adminUsername = "admin";
  private String adminPassword = "test-password";

  public JgitStorageContainer() {
    this(DEFAULT_IMAGE);
  }

  public JgitStorageContainer(String imageName) {
    super(DockerImageName.parse(Objects.requireNonNull(imageName, "imageName")));
    withExposedPorts(HTTP_PORT);
    withEnv("JSH_ADMIN_USERNAME", adminUsername);
    withEnv("JSH_ADMIN_PASSWORD", adminPassword);
    waitingFor(
        Wait.forHttp("/actuator/health/readiness")
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(4)));
  }

  /** Configure the Basic credentials exposed by helper methods. */
  public JgitStorageContainer withAdminCredentials(String username, String password) {
    adminUsername = requireText(username, "username");
    adminPassword = requireText(password, "password");
    withEnv("JSH_ADMIN_USERNAME", adminUsername);
    withEnv("JSH_ADMIN_PASSWORD", adminPassword);
    return self();
  }

  public String getAdminUsername() {
    return adminUsername;
  }

  public String getAdminPassword() {
    return adminPassword;
  }

  public URI getBaseUri() {
    return URI.create("http://" + getHost() + ":" + getMappedPort(HTTP_PORT));
  }

  /** Return a normal Git Smart HTTP clone/fetch/push URI. */
  public URI getRepositoryUri(String repositoryName) {
    return getBaseUri().resolve("/git/" + requireRepositoryName(repositoryName) + ".git");
  }

  static String requireRepositoryName(String repositoryName) {
    String value = requireText(repositoryName, "repositoryName");
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
        || value.endsWith(".git")
        || value.contains("..")) {
      throw new IllegalArgumentException("invalid standalone repository name: " + value);
    }
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return trimmed;
  }
}
