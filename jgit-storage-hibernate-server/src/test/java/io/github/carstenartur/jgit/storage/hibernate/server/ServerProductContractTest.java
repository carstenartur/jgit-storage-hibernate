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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDoesNotExistException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery.PathMatch;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildResult;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildState;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;

class ServerProductContractTest {

  @Test
  void repositoryAdministrationDelegatesAndReturnsStableLocations() {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    RepositoryProjectionScheduler scheduler = mock(RepositoryProjectionScheduler.class);
    GitHistorySearchService historySearch = mock(GitHistorySearchService.class);
    RepositoryAdministrationController controller =
        new RepositoryAdministrationController(repositories, scheduler, historySearch);
    RepositoryProjectionScheduler.IndexStatus idle = status("alpha", RepositoryProjectionScheduler.State.IDLE);
    RepositoryProjectionScheduler.IndexStatus completed =
        status("demo", RepositoryProjectionScheduler.State.COMPLETED);

    when(repositories.list()).thenReturn(List.of("alpha"));
    when(scheduler.status("alpha")).thenReturn(idle);
    List<RepositoryAdministrationController.RepositoryDescriptor> listed = controller.list();
    assertEquals(1, listed.size());
    assertEquals("alpha", listed.getFirst().name());
    assertEquals("/git/alpha.git", listed.getFirst().gitPath());
    assertSame(idle, listed.getFirst().indexStatus());

    when(repositories.create("demo")).thenReturn(new RepositoryName("demo"));
    when(scheduler.schedule("demo")).thenReturn(completed);
    when(scheduler.status("demo")).thenReturn(completed);
    var created = controller.create("demo");
    assertEquals(HttpStatus.CREATED, created.getStatusCode());
    assertEquals(URI.create("/git/demo.git"), created.getHeaders().getLocation());
    assertNotNull(created.getBody());
    assertEquals("demo", created.getBody().name());

    RepositoryDeletionResult deletion = new RepositoryDeletionResult(2, 1, 3);
    when(repositories.delete("demo")).thenReturn(deletion);
    assertSame(deletion, controller.delete("demo"));
    assertSame(completed, controller.reindex("demo"));
    assertSame(completed, controller.indexStatus("demo"));

    verify(repositories, times(2)).create("demo");
    verify(scheduler, times(2)).schedule("demo");
  }

  @Test
  void changeQueryCombinesFiltersCapsLimitAndMapsProjectionDetails() {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    RepositoryProjectionScheduler scheduler = mock(RepositoryProjectionScheduler.class);
    GitHistorySearchService historySearch = mock(GitHistorySearchService.class);
    RepositoryAdministrationController controller =
        new RepositoryAdministrationController(repositories, scheduler, historySearch);
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");
    GitCommitIndex projection = projection("src/Main.java\nREADME.md");
    when(historySearch.findChanges(any(CommitHistoryQuery.class)))
        .thenReturn(List.of(projection));

    List<RepositoryAdministrationController.HistoryEntry> result =
        controller.changes(
            "demo",
            "transaction",
            "author@example.invalid",
            "committer@example.invalid",
            "src/Main.java",
            "EXACT",
            from,
            to,
            5_000);

    ArgumentCaptor<CommitHistoryQuery> queryCaptor =
        ArgumentCaptor.forClass(CommitHistoryQuery.class);
    verify(historySearch).findChanges(queryCaptor.capture());
    CommitHistoryQuery query = queryCaptor.getValue();
    assertEquals("demo", query.repositoryName());
    assertEquals("transaction", query.text());
    assertEquals("author@example.invalid", query.authorEmail());
    assertEquals("committer@example.invalid", query.committerEmail());
    assertEquals("src/Main.java", query.pathFragment());
    assertEquals(PathMatch.EXACT, query.pathMatch());
    assertEquals(from, query.from());
    assertEquals(to, query.to());
    assertEquals(500, query.limit());

    assertEquals(1, result.size());
    RepositoryAdministrationController.HistoryEntry entry = result.getFirst();
    assertEquals("0123456789012345678901234567890123456789", entry.objectId());
    assertEquals("Store Git transactionally", entry.shortMessage());
    assertEquals("Author", entry.authorName());
    assertEquals("author@example.invalid", entry.authorEmail());
    assertEquals("Committer", entry.committerName());
    assertEquals("committer@example.invalid", entry.committerEmail());
    assertEquals(List.of("src/Main.java", "README.md"), entry.changedPaths());
  }

  @Test
  void changeQuerySupportsEveryPathModeBlankInputsAndRejectsUnknownModes() {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    RepositoryProjectionScheduler scheduler = mock(RepositoryProjectionScheduler.class);
    GitHistorySearchService historySearch = mock(GitHistorySearchService.class);
    RepositoryAdministrationController controller =
        new RepositoryAdministrationController(repositories, scheduler, historySearch);
    when(historySearch.findChanges(any(CommitHistoryQuery.class)))
        .thenReturn(List.of(projection(null)));

    List<RepositoryAdministrationController.HistoryEntry> blankResult =
        controller.changes("demo", " ", " ", " ", " ", "ignored", null, null, -1);
    controller.changes("demo", null, null, null, "src", "literal", null, null, 20);
    controller.changes("demo", null, null, null, "main java", "terms", null, null, 20);

    ArgumentCaptor<CommitHistoryQuery> queryCaptor =
        ArgumentCaptor.forClass(CommitHistoryQuery.class);
    verify(historySearch, times(3)).findChanges(queryCaptor.capture());
    List<CommitHistoryQuery> queries = queryCaptor.getAllValues();
    assertEquals(1, queries.get(0).limit());
    assertEquals(null, queries.get(0).pathFragment());
    assertEquals(PathMatch.LITERAL_FRAGMENT, queries.get(1).pathMatch());
    assertEquals(PathMatch.ANALYZED_TERMS, queries.get(2).pathMatch());
    assertEquals(List.of(), blankResult.getFirst().changedPaths());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.changes(
                "demo", null, null, null, "src", "regular-expression", null, null, 20));
  }

  @Test
  void projectionSchedulerCoalescesQueuedRequestsAndPublishesCompletion() throws Exception {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    CommitProjectionRebuilder rebuilder = mock(CommitProjectionRebuilder.class);
    HibernateGitStorage storage = mock(HibernateGitStorage.class);
    Repository repository = mock(Repository.class);
    AtomicReference<Runnable> submitted = new AtomicReference<>();
    TaskExecutor executor =
        task -> {
          if (!submitted.compareAndSet(null, task)) {
            throw new AssertionError("coalesced requests must not submit another task");
          }
        };
    RepositoryProjectionScheduler scheduler =
        new RepositoryProjectionScheduler(repositories, rebuilder, executor);
    when(repositories.open("demo")).thenReturn(storage);
    when(storage.repository()).thenReturn(repository);
    when(rebuilder.rebuild(eq(repository), any(RepositoryName.class)))
        .thenReturn(new RebuildResult("demo", RebuildState.COMPLETED, 1, 3, 3, 0, 0));

    assertEquals(RepositoryProjectionScheduler.State.IDLE, scheduler.status("demo").state());
    assertEquals(RepositoryProjectionScheduler.State.QUEUED, scheduler.schedule("demo").state());
    assertEquals(RepositoryProjectionScheduler.State.QUEUED, scheduler.schedule("demo").state());

    assertNotNull(submitted.get());
    submitted.get().run();

    RepositoryProjectionScheduler.IndexStatus completed = scheduler.status("demo");
    assertEquals(RepositoryProjectionScheduler.State.COMPLETED, completed.state());
    assertEquals(3, completed.indexedCommits());
    verify(repositories).open("demo");
    verify(rebuilder).rebuild(eq(repository), any(RepositoryName.class));
    verify(storage).close();
  }

  @Test
  void projectionSchedulerReportsWorkerFailureAndExecutorRejection() {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    CommitProjectionRebuilder rebuilder = mock(CommitProjectionRebuilder.class);
    when(repositories.open("broken")).thenThrow(new IllegalStateException());
    RepositoryProjectionScheduler failingScheduler =
        new RepositoryProjectionScheduler(repositories, rebuilder, Runnable::run);

    RepositoryProjectionScheduler.IndexStatus failed = failingScheduler.schedule("broken");
    assertEquals(RepositoryProjectionScheduler.State.FAILED, failed.state());
    assertEquals("IllegalStateException: no detail", failed.message());

    IllegalStateException rejection = new IllegalStateException("queue full");
    TaskExecutor rejectingExecutor =
        ignored -> {
          throw rejection;
        };
    RepositoryProjectionScheduler rejectingScheduler =
        new RepositoryProjectionScheduler(repositories, rebuilder, rejectingExecutor);
    assertSame(
        rejection,
        assertThrows(IllegalStateException.class, () -> rejectingScheduler.schedule("demo")));
    RepositoryProjectionScheduler.IndexStatus rejected = rejectingScheduler.status("demo");
    assertEquals(RepositoryProjectionScheduler.State.FAILED, rejected.state());
    assertEquals(
        "Projection executor rejected the task: IllegalStateException", rejected.message());
  }

  @Test
  void apiErrorsAndDiscoveryDocumentRemainStable() {
    ServerApiExceptionHandler handler = new ServerApiExceptionHandler();
    var invalid = handler.invalidRequest(new IllegalArgumentException("bad path mode"));
    assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
    assertEquals("invalid_request", invalid.getBody().get("error"));
    assertEquals("bad path mode", invalid.getBody().get("message"));

    var missingMessage = handler.invalidRequest(new IllegalArgumentException());
    assertEquals("IllegalArgumentException", missingMessage.getBody().get("message"));
    var blankMessage = handler.invalidRequest(new IllegalArgumentException(" "));
    assertEquals("IllegalArgumentException", blankMessage.getBody().get("message"));

    var missing =
        handler.missingRepository(
            new RepositoryDoesNotExistException(new RepositoryName("missing")));
    assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    assertEquals("repository_not_found", missing.getBody().get("error"));

    Map<String, Object> home = new ServerHomeController().home();
    assertEquals("jgit-storage-hibernate-server", home.get("service"));
    assertEquals("/git/{repository}.git", home.get("git"));
    assertEquals("/actuator/health/readiness", home.get("health"));
  }

  private static RepositoryProjectionScheduler.IndexStatus status(
      String repositoryName, RepositoryProjectionScheduler.State state) {
    return new RepositoryProjectionScheduler.IndexStatus(
        repositoryName, state, null, null, null, 0, state.name());
  }

  private static GitCommitIndex projection(String changedPaths) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setObjectId("0123456789012345678901234567890123456789");
    projection.setShortMessage("Store Git transactionally");
    projection.setAuthorName("Author");
    projection.setAuthorEmail("author@example.invalid");
    projection.setCommitterName("Committer");
    projection.setCommitterEmail("committer@example.invalid");
    projection.setAuthorTime(Instant.parse("2026-01-02T03:04:05Z"));
    projection.setCommitterTime(Instant.parse("2026-01-02T04:05:06Z"));
    projection.setChangedPaths(changedPaths);
    return projection;
  }
}
