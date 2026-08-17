/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp.security;

import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationTrace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/** Creates bounded, non-secret credential-audit trace evidence for one Smart HTTP request. */
@FunctionalInterface
public interface SecuritySmartHttpTraceProvider {

  /**
   * Create trace evidence without copying credentials, headers or raw remote addresses.
   *
   * @param request current HTTP request
   * @return non-null bounded trace
   */
  SecurityAuthenticationTrace create(HttpServletRequest request);

  /**
   * Create opaque per-request session and correlation identifiers without remote-address evidence.
   *
   * <p>Applications that already own trusted request/session identifiers should supply an explicit
   * provider instead. Do not copy an untrusted forwarded header directly into audit evidence.
   *
   * @return a provider that creates fresh opaque identifiers for every request
   */
  static SecuritySmartHttpTraceProvider opaquePerRequest() {
    return request -> {
      Objects.requireNonNull(request, "request");
      String requestId = UUID.randomUUID().toString();
      return SecurityAuthenticationTrace.withoutRemoteAddress(
          "smart-http-" + requestId, requestId);
    };
  }
}
