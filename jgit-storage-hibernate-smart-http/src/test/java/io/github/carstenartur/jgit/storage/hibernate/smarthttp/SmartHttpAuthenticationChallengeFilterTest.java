/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmartHttpAuthenticationChallengeFilterTest {

  @Test
  void addsBasicAndBearerChallengesOnceBeforeUnauthorizedCommit() throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
    HttpServletResponse response = response(challenges, status);

    SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git")
        .doFilter(
            request(),
            response,
            (servletRequest, servletResponse) -> {
              HttpServletResponse http = (HttpServletResponse) servletResponse;
              http.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
              http.sendError(HttpServletResponse.SC_UNAUTHORIZED, "denied");
            });

    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, status.get());
    assertEquals(
        List.of(
            "Basic realm=\"Git\", charset=\"UTF-8\"",
            "Bearer realm=\"Git\""),
        challenges);
  }

  @Test
  void advertisesSingleSchemesAndNeverChallengesOtherStatuses() throws Exception {
    assertEquals(
        List.of("Basic realm=\"Git\", charset=\"UTF-8\""),
        challenges(SmartHttpAuthenticationChallengeFilter.basic("Git"), 401));
    assertEquals(
        List.of("Bearer realm=\"Git\""),
        challenges(SmartHttpAuthenticationChallengeFilter.bearer("Git"), 401));
    assertTrue(
        challenges(SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git"), 403)
            .isEmpty());
    assertTrue(
        challenges(SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git"), 200)
            .isEmpty());
  }

  @Test
  void addsForFinalStatusAndDoesNotLeakFromATransientUnauthorizedStatus() throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
    SmartHttpAuthenticationChallengeFilter filter =
        SmartHttpAuthenticationChallengeFilter.basic("Git");

    filter.doFilter(
        request(),
        response(challenges, status),
        (servletRequest, servletResponse) ->
            ((HttpServletResponse) servletResponse)
                .setStatus(HttpServletResponse.SC_UNAUTHORIZED));
    assertEquals(
        List.of("Basic realm=\"Git\", charset=\"UTF-8\""), challenges);

    challenges.clear();
    status.set(HttpServletResponse.SC_OK);
    filter.doFilter(
        request(),
        response(challenges, status),
        (servletRequest, servletResponse) -> {
          HttpServletResponse http = (HttpServletResponse) servletResponse;
          http.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          http.setStatus(HttpServletResponse.SC_OK);
        });
    assertTrue(challenges.isEmpty());
    assertEquals(HttpServletResponse.SC_OK, status.get());
  }

  @Test
  void addsBeforeAnUnauthorizedBufferFlush() throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
    SmartHttpAuthenticationChallengeFilter.bearer("Git")
        .doFilter(
            request(),
            response(challenges, status),
            (servletRequest, servletResponse) -> {
              HttpServletResponse http = (HttpServletResponse) servletResponse;
              http.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
              http.flushBuffer();
            });
    assertEquals(List.of("Bearer realm=\"Git\""), challenges);
  }

  @Test
  void resetDiscardsATransientStatusAndAllowsAReplacementUnauthorizedResponse()
      throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
    SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git")
        .doFilter(
            request(),
            response(challenges, status),
            (servletRequest, servletResponse) -> {
              HttpServletResponse http = (HttpServletResponse) servletResponse;
              http.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
              http.reset();
              http.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            });

    assertEquals(
        List.of(
            "Basic realm=\"Git\", charset=\"UTF-8\"",
            "Bearer realm=\"Git\""),
        challenges);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, status.get());
  }

  @Test
  void validatesRealmAndRequiresAnHttpResponse() {
    List<String> invalidRealms =
        List.of("", " ", "bad\"realm", "bad\\realm", "bad\nrealm", "ä", "x".repeat(129));
    for (String realm : invalidRealms) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SmartHttpAuthenticationChallengeFilter.basic(realm));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> SmartHttpAuthenticationChallengeFilter.bearer(null));
    assertThrows(
        jakarta.servlet.ServletException.class,
        () ->
            SmartHttpAuthenticationChallengeFilter.basic("Git")
                .doFilter(request(), nonHttpResponse(), (request, response) -> {}));
  }

  private static List<String> challenges(
      SmartHttpAuthenticationChallengeFilter filter, int responseStatus) throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
    filter.doFilter(
        request(),
        response(challenges, status),
        (servletRequest, servletResponse) ->
            ((HttpServletResponse) servletResponse).sendError(responseStatus));
    assertEquals(responseStatus, status.get());
    return challenges;
  }

  private static ServletRequest request() {
    return (ServletRequest)
        Proxy.newProxyInstance(
            SmartHttpAuthenticationChallengeFilterTest.class.getClassLoader(),
            new Class<?>[] {ServletRequest.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static ServletResponse nonHttpResponse() {
    return (ServletResponse)
        Proxy.newProxyInstance(
            SmartHttpAuthenticationChallengeFilterTest.class.getClassLoader(),
            new Class<?>[] {ServletResponse.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static HttpServletResponse response(
      List<String> challenges, AtomicInteger status) {
    AtomicBoolean committed = new AtomicBoolean();
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            SmartHttpAuthenticationChallengeFilterTest.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, arguments) -> {
              switch (method.getName()) {
                case "addHeader" -> {
                  if ("WWW-Authenticate".equals(arguments[0])) {
                    challenges.add((String) arguments[1]);
                  }
                  return null;
                }
                case "sendError" -> {
                  status.set((Integer) arguments[0]);
                  committed.set(true);
                  return null;
                }
                case "setStatus" -> {
                  status.set((Integer) arguments[0]);
                  return null;
                }
                case "getStatus" -> {
                  return status.get();
                }
                case "flushBuffer" -> {
                  committed.set(true);
                  return null;
                }
                case "isCommitted" -> {
                  return committed.get();
                }
                case "reset" -> {
                  challenges.clear();
                  status.set(HttpServletResponse.SC_OK);
                  committed.set(false);
                  return null;
                }
                case "toString" -> {
                  return "SmartHttpChallengeTestResponse";
                }
                case "hashCode" -> {
                  return System.identityHashCode(proxy);
                }
                case "equals" -> {
                  return proxy == arguments[0];
                }
                default -> {
                  return defaultValue(method.getReturnType());
                }
              }
            });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    return null;
  }
}
