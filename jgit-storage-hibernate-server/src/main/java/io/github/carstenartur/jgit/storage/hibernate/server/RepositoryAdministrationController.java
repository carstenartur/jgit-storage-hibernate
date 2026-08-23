/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery.Builder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Repository administration and searchable-history HTTP API. */
@RestController
@RequestMapping("/api/repositories")
public class RepositoryAdministrationController {

  private final JgitRepositoryService repositories;
  private final RepositoryProjectionScheduler scheduler;
  private final GitHistorySearchService historySearch;

  public RepositoryAdministrationController(
      JgitRepositoryService repositories,
      RepositoryProjectionScheduler scheduler,
      GitHistorySearchService historySearch) {
    this.repositories = repositories;
    this.scheduler = scheduler;
    this.historySearch = historySearch;
  }

  @GetMapping
  public List<RepositoryDescriptor> list() {
    return repositories.list().stream().map(this::descriptor).toList();
  }

  @PostMapping("/{name}")
  public ResponseEntity<RepositoryDescriptor> create(@PathVariable("name") String name) {
    String repositoryName = repositories.create(name).value();
    scheduler.schedule(repositoryName);
    return ResponseEntity.created(URI.create("/git/" + repositoryName + ".git"))
        .body(descriptor(repositoryName));
  }

  @DeleteMapping("/{name}")
  public RepositoryDeletionResult delete(@PathVariable("name") String name) {
    return repositories.delete(name);
  }

  @PostMapping("/{name}/reindex")
  public RepositoryProjectionScheduler.IndexStatus reindex(@PathVariable("name") String name) {
    repositories.create(name);
    return scheduler.schedule(name);
  }

  @GetMapping("/{name}/index-status")
  public RepositoryProjectionScheduler.IndexStatus indexStatus(@PathVariable("name") String name) {
    return scheduler.status(name);
  }

  @GetMapping("/{name}/changes")
  public List<HistoryEntry> changes(
      @PathVariable("name") String name,
      @RequestParam(name = "text", required = false) String text,
      @RequestParam(name = "author", required = false) String author,
      @RequestParam(name = "committer", required = false) String committer,
      @RequestParam(name = "path", required = false) String path,
      @RequestParam(name = "pathMode", defaultValue = "literal") String pathMode,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to,
      @RequestParam(name = "limit", defaultValue = "100") int limit) {
    JgitRepositoryService.requireSafeName(name);
    Builder builder = CommitHistoryQuery.forRepository(name).limit(Math.max(1, Math.min(limit, 500)));
    if (text != null && !text.isBlank()) {
      builder.matchingText(text);
    }
    if (author != null && !author.isBlank()) {
      builder.authoredBy(author);
    }
    if (committer != null && !committer.isBlank()) {
      builder.committedBy(committer);
    }
    if (path != null && !path.isBlank()) {
      switch (pathMode.toLowerCase(java.util.Locale.ROOT)) {
        case "literal" -> builder.touchingPath(path);
        case "terms" -> builder.touchingPathTerms(path);
        case "exact" -> builder.touchingExactPath(path);
        default -> throw new IllegalArgumentException("pathMode must be literal, terms or exact");
      }
    }
    if (from != null) {
      builder.from(from);
    }
    if (to != null) {
      builder.to(to);
    }
    return historySearch.findChanges(builder.build()).stream().map(HistoryEntry::from).toList();
  }

  private RepositoryDescriptor descriptor(String name) {
    return new RepositoryDescriptor(name, "/git/" + name + ".git", scheduler.status(name));
  }

  /** Public repository location and projection state. */
  public record RepositoryDescriptor(
      String name, String gitPath, RepositoryProjectionScheduler.IndexStatus indexStatus) {}

  /** Compact REST representation retaining the changed paths that make the query useful. */
  public record HistoryEntry(
      String objectId,
      String shortMessage,
      String authorName,
      String authorEmail,
      String committerName,
      String committerEmail,
      Instant authorTime,
      Instant committerTime,
      List<String> changedPaths) {

    private static HistoryEntry from(GitCommitIndex projection) {
      String value = projection.getChangedPaths();
      List<String> paths =
          value == null || value.isBlank() ? List.of() : value.lines().toList();
      return new HistoryEntry(
          projection.getObjectId(),
          projection.getShortMessage(),
          projection.getAuthorName(),
          projection.getAuthorEmail(),
          projection.getCommitterName(),
          projection.getCommitterEmail(),
          projection.getAuthorTime(),
          projection.getCommitterTime(),
          paths);
    }
  }
}
