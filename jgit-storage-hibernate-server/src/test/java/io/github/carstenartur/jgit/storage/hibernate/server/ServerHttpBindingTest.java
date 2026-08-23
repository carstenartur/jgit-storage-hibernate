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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery.PathMatch;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServerHttpBindingTest {

  @Test
  void bindsDocumentedRepositoryAndQueryParametersWithoutCompilerParameterMetadata()
      throws Exception {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    RepositoryProjectionScheduler scheduler = mock(RepositoryProjectionScheduler.class);
    GitHistorySearchService historySearch = mock(GitHistorySearchService.class);
    RepositoryAdministrationController controller =
        new RepositoryAdministrationController(repositories, scheduler, historySearch);
    RepositoryProjectionScheduler.IndexStatus completed =
        new RepositoryProjectionScheduler.IndexStatus(
            "smoke",
            RepositoryProjectionScheduler.State.COMPLETED,
            null,
            null,
            null,
            0,
            "Projection rebuild completed");

    when(repositories.create("smoke")).thenReturn(new RepositoryName("smoke"));
    when(scheduler.schedule("smoke")).thenReturn(completed);
    when(scheduler.status("smoke")).thenReturn(completed);
    when(historySearch.findChanges(any(CommitHistoryQuery.class))).thenReturn(List.of());

    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ServerApiExceptionHandler())
            .build();

    mvc.perform(post("/api/repositories/smoke"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/git/smoke.git"));

    mvc.perform(
            get("/api/repositories/smoke/changes")
                .queryParam("text", "transaction")
                .queryParam("path", "README.md")
                .queryParam("pathMode", "exact")
                .queryParam("limit", "25"))
        .andExpect(status().isOk());

    ArgumentCaptor<CommitHistoryQuery> query =
        ArgumentCaptor.forClass(CommitHistoryQuery.class);
    verify(historySearch).findChanges(query.capture());
    assertEquals("smoke", query.getValue().repositoryName());
    assertEquals("transaction", query.getValue().text());
    assertEquals("README.md", query.getValue().pathFragment());
    assertEquals(PathMatch.EXACT, query.getValue().pathMatch());
    assertEquals(25, query.getValue().limit());
  }
}
