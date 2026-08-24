/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ServerDefaultConfigurationTest {

  @Test
  void letsHibernateManageAutoCommitForTheApplicationDataSource() throws IOException {
    String configuration;
    try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
      configuration = new String(input.readAllBytes(), UTF_8);
    }

    assertFalse(
        configuration.contains("hibernate.connection.provider_disables_autocommit"),
        "The provider_disables_autocommit optimization is valid only when every pooled connection "
            + "already has autoCommit disabled; the server DataSource does not guarantee that contract");
  }
}
