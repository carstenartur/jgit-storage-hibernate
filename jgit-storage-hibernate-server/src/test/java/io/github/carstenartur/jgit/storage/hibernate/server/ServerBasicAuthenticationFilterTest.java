/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServerBasicAuthenticationFilterTest {

  @Test
  void acceptsMatchingUtf8BasicCredentialsAndPropagatesPrincipal() throws Exception {
    JgitStorageServerProperties properties = properties();
    ServerBasicAuthenticationFilter filter =
        new ServerBasicAuthenticationFilter(properties.getAuthentication());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repositories");
    request.addHeader("Authorization", basic("admin", "secret"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    ServerPrincipal principal =
        assertInstanceOf(
            ServerPrincipal.class,
            ((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getUserPrincipal());
    assertEquals("admin", principal.getName());
  }

  @Test
  void rejectsWrongCredentialsWithAChallenge() throws Exception {
    JgitStorageServerProperties properties = properties();
    ServerBasicAuthenticationFilter filter =
        new ServerBasicAuthenticationFilter(properties.getAuthentication());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/git/demo.git/info/refs");
    request.addHeader("Authorization", basic("admin", "wrong"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
    assertEquals("Basic realm=\"Git\", charset=\"UTF-8\"", response.getHeader("WWW-Authenticate"));
  }

  @Test
  void rejectsPlainHttpBeforeInspectingCredentialsWhenConfigured() throws Exception {
    JgitStorageServerProperties properties = properties();
    properties.getAuthentication().setRequireSecureTransport(true);
    ServerBasicAuthenticationFilter filter =
        new ServerBasicAuthenticationFilter(properties.getAuthentication());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repositories");
    request.addHeader("Authorization", basic("admin", "secret"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
  }

  private static JgitStorageServerProperties properties() {
    JgitStorageServerProperties properties = new JgitStorageServerProperties();
    properties.getAuthentication().setUsername("admin");
    properties.getAuthentication().setPassword("secret");
    return properties;
  }

  private static String basic(String username, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }
}
