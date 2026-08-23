/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryDoesNotExistException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable JSON errors for malformed names and absent repositories. */
@RestControllerAdvice
public class ServerApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException failure) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", "invalid_request", "message", safeMessage(failure)));
  }

  @ExceptionHandler(RepositoryDoesNotExistException.class)
  ResponseEntity<Map<String, String>> missingRepository(
      RepositoryDoesNotExistException failure) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "repository_not_found", "message", safeMessage(failure)));
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }
}
