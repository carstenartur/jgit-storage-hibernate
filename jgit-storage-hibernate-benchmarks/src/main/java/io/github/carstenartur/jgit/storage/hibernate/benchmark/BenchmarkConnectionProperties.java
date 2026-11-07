/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/** Connection properties shared by benchmark forks that retain credential-free evidence. */
public final class BenchmarkConnectionProperties {

  static final String CONNECTION_PROPERTIES_FILE_PROPERTY =
      "jgit.storage.benchmark.connection-properties-file";

  private BenchmarkConnectionProperties() {}

  /** Resolve a JVM property first, then the owner-readable fork connection file. */
  public static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String connectionPropertiesFile =
        System.getProperty(CONNECTION_PROPERTIES_FILE_PROPERTY);
    if (connectionPropertiesFile != null && !connectionPropertiesFile.isBlank()) {
      Properties connectionProperties = new Properties();
      try (InputStream input =
          Files.newInputStream(Path.of(connectionPropertiesFile))) {
        connectionProperties.load(input);
      } catch (IOException failure) {
        throw new IllegalStateException(
            "Cannot read temporary benchmark connection properties", failure);
      }
      value = connectionProperties.getProperty(name);
    }
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }

  /** Suppress Hibernate messages that expose connection metadata in retained output. */
  public static void suppressConnectionMetadataLogging() {
    java.util.logging.Level warning = java.util.logging.Level.WARNING;
    Logger.getLogger("").setLevel(warning);
    Logger.getLogger("org.hibernate").setLevel(warning);
    Logger.getLogger("org.hibernate.orm.connections.pooling").setLevel(warning);
    Logger.getLogger(
            "org.hibernate.engine.jdbc.env.internal.JdbcEnvironmentInitiator")
        .setLevel(warning);
  }
}
