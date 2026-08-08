/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchContentPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchContentPolicyH2Test {

  @Test
  void appliesExtensionTextGeneratedBinaryAndMinifiedFilters() {
    Properties properties = baseProperties();
    properties.put(SearchContentPolicy.ALLOW_EXTENSIONS_PROPERTY, "java,js,txt");
    properties.put(SearchContentPolicy.DENY_EXTENSIONS_PROPERTY, ".txt");
    properties.put(SearchContentPolicy.REJECT_BINARY_PROPERTY, "true");
    properties.put(SearchContentPolicy.REJECT_INVALID_UTF8_PROPERTY, "true");
    properties.put(SearchContentPolicy.SKIP_GENERATED_PROPERTY, "true");
    properties.put(SearchContentPolicy.SKIP_MINIFIED_PROPERTY, "true");

    try (HibernateSessionFactoryProvider provider = provider(properties)) {
      SearchContentPolicy policy = SearchContentPolicy.resolve(provider.getSessionFactory());

      assertTrue(policy.acceptsPath("src/Main.java"));
      assertFalse(policy.acceptsPath("docs/readme.txt"));
      assertFalse(policy.acceptsPath("src/generated/Generated.java"));
      assertFalse(policy.acceptsPath("assets/logo.png"));
      assertEquals(
          "class Main {}",
          policy.decode("src/Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8)));
      assertNull(policy.decode("src/Main.java", new byte[] {'a', 0, 'b'}));
      assertNull(policy.decode("src/Main.java", new byte[] {(byte) 0xC3, 0x28}));
      assertNull(
          policy.decode(
              "web/app.min.js", "x=".repeat(1_500).getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void defaultPolicyRetainsPreviousBoundsAndPermissiveUtf8Behavior() {
    try (HibernateSessionFactoryProvider provider = provider(baseProperties())) {
      SearchContentPolicy policy = SearchContentPolicy.resolve(provider.getSessionFactory());
      assertEquals(SearchContentPolicy.DEFAULT_MAX_FILE_BYTES, policy.maxFileBytes());
      assertEquals(SearchContentPolicy.DEFAULT_MAX_COMMIT_CHARS, policy.maxCommitChars());
      assertFalse(policy.rejectBinary());
      assertFalse(policy.rejectInvalidUtf8());
      assertTrue(policy.acceptsPath("anything/data.bin"));
      assertEquals("a\u0000b", policy.decode("anything/data.bin", new byte[] {'a', 0, 'b'}));
    }
  }

  @Test
  void configuredBoundsRemainHardBounded() {
    Properties properties = baseProperties();
    properties.put(
        SearchContentPolicy.MAX_FILE_BYTES_PROPERTY,
        Integer.toString(SearchContentPolicy.ABSOLUTE_MAX_FILE_BYTES + 1));
    try (HibernateSessionFactoryProvider provider = provider(properties)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SearchContentPolicy.resolve(provider.getSessionFactory()));
    }
  }

  private static HibernateSessionFactoryProvider provider(Properties properties) {
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }

  private static Properties baseProperties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:content-policy-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
