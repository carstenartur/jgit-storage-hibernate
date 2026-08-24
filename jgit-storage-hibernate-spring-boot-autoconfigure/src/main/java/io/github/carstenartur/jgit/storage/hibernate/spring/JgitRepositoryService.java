/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Spring-friendly administration facade over the framework-neutral repository factory. */
public final class JgitRepositoryService {

  private static final Pattern SAFE_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

  private final DefaultHibernateRepositoryFactory repositoryFactory;
  private final SessionFactory sessionFactory;
  private final String defaultBranchRef;

  public JgitRepositoryService(
      DefaultHibernateRepositoryFactory repositoryFactory, SessionFactory sessionFactory) {
    this(repositoryFactory, sessionFactory, "main");
  }

  public JgitRepositoryService(
      DefaultHibernateRepositoryFactory repositoryFactory,
      SessionFactory sessionFactory,
      String defaultBranch) {
    this.repositoryFactory = Objects.requireNonNull(repositoryFactory, "repositoryFactory");
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.defaultBranchRef = requireDefaultBranchRef(defaultBranch);
  }

  /** Create the logical repository if absent and return its canonical name. */
  public RepositoryName create(String repositoryName) {
    RepositoryName name = requireSafeName(repositoryName);
    try (HibernateGitStorage storage = repositoryFactory.open(name)) {
      initializeUnbornHead(storage.repository(), name);
      return name;
    }
  }

  /** Open or create a repository using the normal Core facade. */
  public HibernateGitStorage open(String repositoryName) {
    return repositoryFactory.open(requireSafeName(repositoryName));
  }

  /** List every durable logical repository in deterministic name order. */
  public List<String> list() {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "SELECT r.repositoryName FROM GitRepositoryLifecycleEntity r ORDER BY r.repositoryName",
              String.class)
          .getResultList();
    }
  }

  /** Delete one repository using Core's lock-bound lifecycle contract. */
  public RepositoryDeletionResult delete(String repositoryName) {
    return repositoryFactory.deleteRepository(requireSafeName(repositoryName));
  }

  /** Validate the standalone/server-safe repository naming subset. */
  public static RepositoryName requireSafeName(String repositoryName) {
    String value = Objects.requireNonNull(repositoryName, "repositoryName").trim();
    if (!SAFE_NAME.matcher(value).matches()
        || value.endsWith(".git")
        || value.contains("..")) {
      throw new IllegalArgumentException(
          "repository name must use 1-255 letters, digits, '.', '_' or '-', must not end in .git and must not contain '..'");
    }
    return new RepositoryName(value);
  }

  private void initializeUnbornHead(Repository repository, RepositoryName repositoryName) {
    try {
      List<Ref> branches = repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
      if (!branches.isEmpty()) {
        return;
      }

      Ref head = repository.exactRef(Constants.HEAD);
      if (head != null && !head.isSymbolic() && head.getObjectId() != null) {
        return;
      }
      if (head != null
          && head.isSymbolic()
          && defaultBranchRef.equals(head.getTarget().getName())) {
        return;
      }

      RefUpdate.Result result = repository.updateRef(Constants.HEAD).link(defaultBranchRef);
      if (result != RefUpdate.Result.NEW
          && result != RefUpdate.Result.FORCED
          && result != RefUpdate.Result.NO_CHANGE) {
        throw new HibernateStorageException(
            "Could not set HEAD of repository "
                + repositoryName
                + " to "
                + defaultBranchRef
                + ": "
                + result);
      }
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not initialize HEAD of repository "
              + repositoryName
              + " to "
              + defaultBranchRef,
          exception);
    }
  }

  private static String requireDefaultBranchRef(String defaultBranch) {
    String value = Objects.requireNonNull(defaultBranch, "defaultBranch").trim();
    String refName = Constants.R_HEADS + value;
    if (value.startsWith(Constants.R_REFS) || !Repository.isValidRefName(refName)) {
      throw new IllegalArgumentException(
          "default branch must be a valid short branch name but was '" + defaultBranch + "'");
    }
    return refName;
  }
}
