/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;

/**
 * Composed PostgreSQL and Git server environment for black-box application integration tests.
 *
 * <p>The environment starts the production server image rather than a divergent in-memory Git
 * implementation. Tests can use a normal Git client, the administration API and JDBC inspection
 * views in the same scenario.
 */
public final class JgitStorageEnvironment implements Startable, AutoCloseable {

  public static final String DEFAULT_POSTGRES_IMAGE = "postgres:17.10-alpine";

  private final Network network = Network.newNetwork();
  private final PostgreSQLContainer<?> database;
  private final JgitStorageContainer server;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicBoolean started = new AtomicBoolean();

  public JgitStorageEnvironment() {
    this(JgitStorageContainer.DEFAULT_IMAGE);
  }

  public JgitStorageEnvironment(String serverImage) {
    database =
        new PostgreSQLContainer<>(DockerImageName.parse(DEFAULT_POSTGRES_IMAGE))
            .withDatabaseName("jgit")
            .withUsername("jgit")
            .withPassword("jgit-test")
            .withNetwork(network)
            .withNetworkAliases("postgres");
    server = new JgitStorageContainer(serverImage).withNetwork(network);
  }

  /** Override the standalone administrator credentials before start. */
  public JgitStorageEnvironment withAdminCredentials(String username, String password) {
    requireNotStarted();
    server.withAdminCredentials(username, password);
    return this;
  }

  @Override
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    boolean databaseStarted = false;
    try {
      database.start();
      databaseStarted = true;
      server
          .withEnv("JSH_JDBC_URL", "jdbc:postgresql://postgres:5432/jgit")
          .withEnv("JSH_JDBC_USERNAME", database.getUsername())
          .withEnv("JSH_JDBC_PASSWORD", database.getPassword())
          .withEnv("JSH_SEARCH_DIRECTORY", "/var/lib/jgit-storage/search")
          .withEnv("JSH_INSPECTION_VIEWS_ENABLED", "true");
      server.start();
    } catch (RuntimeException failure) {
      started.set(false);
      if (databaseStarted) {
        database.stop();
      }
      throw failure;
    }
  }

  @Override
  public void stop() {
    if (!started.compareAndSet(true, false)) {
      return;
    }
    try {
      server.stop();
    } finally {
      try {
        database.stop();
      } finally {
        network.close();
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  public JgitStorageContainer getServer() {
    return server;
  }

  public PostgreSQLContainer<?> getDatabase() {
    return database;
  }

  public URI getRepositoryUri(String repositoryName) {
    requireStarted();
    return server.getRepositoryUri(repositoryName);
  }

  /** Return direct JDBC access to the same PostgreSQL database used by the server. */
  public DataSource getInspectionDataSource() {
    requireStarted();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(database.getJdbcUrl());
    dataSource.setUser(database.getUsername());
    dataSource.setPassword(database.getPassword());
    return dataSource;
  }

  public String getJdbcUrl() {
    requireStarted();
    return database.getJdbcUrl();
  }

  public String getInspectionUsername() {
    return database.getUsername();
  }

  public String getInspectionPassword() {
    return database.getPassword();
  }

  /** Create an empty repository through the same authenticated administration API used in Docker. */
  public URI createRepository(String repositoryName) {
    requireStarted();
    String name = JgitStorageContainer.requireRepositoryName(repositoryName);
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(
                    server.getBaseUri().resolve("/api/repositories/" + name))
                .header("Authorization", authorization())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw new IllegalStateException(
          "Repository creation failed with HTTP "
              + response.statusCode()
              + ": "
              + response.body());
    }
    return getRepositoryUri(name);
  }

  /** Wait until the asynchronous Search projection reaches a terminal successful state. */
  public void awaitProjection(String repositoryName, Duration timeout) {
    requireStarted();
    String name = JgitStorageContainer.requireRepositoryName(repositoryName);
    Instant deadline = Instant.now().plus(Objects.requireNonNull(timeout, "timeout"));
    String lastBody = "";
    while (Instant.now().isBefore(deadline)) {
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(
                      server.getBaseUri().resolve(
                          "/api/repositories/" + name + "/index-status"))
                  .header("Authorization", authorization())
                  .GET()
                  .build());
      lastBody = response.body();
      if (response.statusCode() == 200) {
        try {
          JsonNode value = objectMapper.readTree(lastBody);
          String state = value.path("state").asText();
          if ("COMPLETED".equals(state)) {
            return;
          }
          if ("FAILED".equals(state)) {
            throw new IllegalStateException("Projection rebuild failed: " + lastBody);
          }
        } catch (IOException malformed) {
          throw new IllegalStateException("Invalid projection status JSON: " + lastBody, malformed);
        }
      }
      sleep(Duration.ofMillis(250));
    }
    throw new IllegalStateException(
        "Projection did not complete within " + timeout + "; last response: " + lastBody);
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("Could not call the jgit-storage-hibernate server", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling the server", exception);
    }
  }

  private String authorization() {
    String credentials = server.getAdminUsername() + ":" + server.getAdminPassword();
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private void requireStarted() {
    if (!started.get()) {
      throw new IllegalStateException("JgitStorageEnvironment has not been started");
    }
  }

  private void requireNotStarted() {
    if (started.get()) {
      throw new IllegalStateException("Container configuration cannot change after start");
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the projection", exception);
    }
  }
}
