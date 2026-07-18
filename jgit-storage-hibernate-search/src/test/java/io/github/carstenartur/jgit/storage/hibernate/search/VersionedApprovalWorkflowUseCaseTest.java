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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;

/** Executable version of the application-oriented example documented in the repository README. */
class VersionedApprovalWorkflowUseCaseTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String REPOSITORY_NAME = "approval-workflows";
  private static final String MAIN_REF = "refs/heads/main";
  private static final String WORKFLOW_PATH = "workflows/purchase-approval.yaml";

  @Test
  void publishesAndQueriesApprovalWorkflowHistory() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    String databaseName = "approval-workflow-use-case-" + TEST_COUNTER.incrementAndGet();

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(
                h2Properties(databaseName), SearchEntities.annotatedClasses());
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName(REPOSITORY_NAME))) {
      Repository repository = storage.repository();

      ObjectId initial =
          commitWorkflow(
              repository,
              null,
              "Publish purchase approval workflow",
              """
              id: purchase-approval
              approvalLimit: 10000
              approvalMode: manager
              """);
      publishMain(repository, ObjectId.zeroId(), initial, RefUpdate.Result.NEW);

      ObjectId fourEyes =
          commitWorkflow(
              repository,
              initial,
              "Require dual control for high-value purchases",
              """
              id: purchase-approval
              approvalLimit: 10000
              approvalMode: dualcontrol
              """);
      publishMain(repository, initial, fourEyes, RefUpdate.Result.FAST_FORWARD);

      CommitIndexer indexer = new CommitIndexer(provider.getSessionFactory(), REPOSITORY_NAME);
      indexer.indexCommit(repository, initial);
      indexer.indexCommit(repository, fourEyes);

      GitHistorySearchService history =
          new GitHistorySearchService(provider.getSessionFactory());
      List<GitCommitIndex> policyHits =
          history.searchCommitText(REPOSITORY_NAME, "dualcontrol", 10);
      List<GitCommitIndex> pathHits =
          history.findByPath(REPOSITORY_NAME, "purchase-approval.yaml", 10);

      assertEquals(1, policyHits.size());
      assertEquals(fourEyes.name(), policyHits.getFirst().getObjectId());
      assertEquals(2, pathHits.size());

      Ref main = repository.exactRef(MAIN_REF);
      assertEquals(fourEyes, main.getObjectId());
      assertTrue(repository.getRefDatabase().performsAtomicTransactions());
    }
  }

  private static ObjectId commitWorkflow(
      Repository repository, ObjectId parent, String message, String workflowYaml) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, workflowYaml.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(WORKFLOW_PATH, FileMode.REGULAR_FILE, blob);

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent actor = new PersonIdent("Approval Service", "approval@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);

      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static void publishMain(
      Repository repository,
      ObjectId expectedOldId,
      ObjectId newId,
      RefUpdate.Result expectedResult)
      throws Exception {
    RefUpdate update = repository.updateRef(MAIN_REF);
    update.setExpectedOldObjectId(expectedOldId);
    update.setNewObjectId(newId);
    update.setRefLogMessage("publish approval workflow", false);
    assertEquals(expectedResult, update.update());
  }

  private static Properties h2Properties(String databaseName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
