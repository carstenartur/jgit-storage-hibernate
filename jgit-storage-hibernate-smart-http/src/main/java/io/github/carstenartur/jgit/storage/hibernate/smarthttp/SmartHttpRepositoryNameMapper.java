/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

/** Maps JGit's URL-derived repository name to the immutable database storage identity. */
@FunctionalInterface
public interface SmartHttpRepositoryNameMapper {

  /**
   * Map one request name without creating a repository.
   *
   * @param requestName name parsed by JGit from the request URL
   * @return canonical logical repository name
   * @throws RepositoryNotFoundException when the name is invalid or intentionally hidden
   */
  RepositoryName map(String requestName) throws RepositoryNotFoundException;

  /**
   * Return the default strict mapper.
   *
   * <p>It removes one trailing {@code .git}, permits slash-separated logical namespaces and rejects
   * empty/dot segments, leading or trailing slashes, backslashes, controls, surrounding whitespace
   * and names exceeding Core's 255-character schema bound.
   *
   * @return reusable strict mapper
   */
  static SmartHttpRepositoryNameMapper strict() {
    return StrictSmartHttpRepositoryNameMapper.INSTANCE;
  }
}

final class StrictSmartHttpRepositoryNameMapper implements SmartHttpRepositoryNameMapper {

  static final StrictSmartHttpRepositoryNameMapper INSTANCE =
      new StrictSmartHttpRepositoryNameMapper();
  private static final int MAX_REPOSITORY_NAME_LENGTH = 255;

  private StrictSmartHttpRepositoryNameMapper() {}

  @Override
  public RepositoryName map(String requestName) throws RepositoryNotFoundException {
    if (requestName == null
        || requestName.isBlank()
        || !requestName.equals(requestName.strip())) {
      throw hidden(requestName);
    }

    String canonical =
        requestName.endsWith(".git")
            ? requestName.substring(0, requestName.length() - ".git".length())
            : requestName;
    if (canonical.isBlank()
        || canonical.length() > MAX_REPOSITORY_NAME_LENGTH
        || canonical.startsWith("/")
        || canonical.endsWith("/")
        || canonical.contains("\\")
        || canonical.contains("//")) {
      throw hidden(requestName);
    }
    for (int index = 0; index < canonical.length(); index++) {
      if (Character.isISOControl(canonical.charAt(index))) {
        throw hidden(requestName);
      }
    }
    for (String segment : canonical.split("/", -1)) {
      if (segment.isEmpty() || Objects.equals(segment, ".") || Objects.equals(segment, "..")) {
        throw hidden(requestName);
      }
    }
    return new RepositoryName(canonical);
  }

  private static RepositoryNotFoundException hidden(String requestName) {
    return new RepositoryNotFoundException(requestName == null ? "" : requestName);
  }
}
