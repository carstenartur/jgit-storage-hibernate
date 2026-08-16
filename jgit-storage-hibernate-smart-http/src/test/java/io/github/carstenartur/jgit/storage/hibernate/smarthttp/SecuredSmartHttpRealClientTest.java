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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessPolicy;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.http.server.GitServlet;
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
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class SecuredSmartHttpRealClientTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName REPOSITORY = new RepositoryName("team/demo");
  private static final String MAIN = "refs/heads/main";
  private static final String PERSONAL = "refs/heads/users/alice/topic";

  @TempDir Path temporaryDirectory;

  @Test
  void fetchOnlyServletSupportsCloneAndFetchButRejectsPush() throws Exception {
    MutablePolicy policy = MutablePolicy.readable();
    try (SmartHttpFixture fixture = SmartHttpFixture.fetchOnly(policy)) {
      ObjectId initial = fixture.initializeRepository();
      String remote = fixture.remoteUri();

      Path cloneDirectory = temporaryDirectory.resolve("fetch-only-clone.git");
      try (Git clone =
          Git.cloneRepository()
              .setURI(remote)
              .setDirectory(cloneDirectory.toFile())
              .setBare(true)
              .setBranch(MAIN)
              .call()) {
        assertEquals(initial, clone.getRepository().resolve(MAIN));

        ObjectId next = fixture.appendMainCommit("server-update");
        clone.fetch().setRemote("origin").call();
        assertEquals(next, clone.getRepository().resolve(MAIN));
      }

      try (Git local = createLocalRepository(temporaryDirectory.resolve("fetch-only-push"))) {
        RefSpec create = new RefSpec("HEAD:refs/heads/users/alice/disabled");
        assertThrows(
            TransportException.class,
            () -> local.push().setRemote(remote).setRefSpecs(create).call());
      }
      assertNull(fixture.ref("refs/heads/users/alice/disabled"));
    }
  }

  @Test
  void admittedPushStillUsesExactCoreRulesForUpdateForceDeleteAndAtomicBatches()
      throws Exception {
    MutablePolicy policy =
        MutablePolicy.readable()
            .allowWrites(
                request ->
                    request.refName() != null
                        && request.refName().startsWith("refs/heads/users/alice/")
                        && (request.operation() == RepositoryAccessOperation.CREATE_REF
                            || request.operation() == RepositoryAccessOperation.UPDATE_REF));
    try (SmartHttpFixture fixture = SmartHttpFixture.pushEnabled(policy)) {
      fixture.initializeRepository();
      String remote = fixture.remoteUri();

      try (Git local = createLocalRepository(temporaryDirectory.resolve("push-client"))) {
        ObjectId first = local.getRepository().resolve(Constants.HEAD);
        Map<String, RemoteRefUpdate.Status> create =
            push(local, remote, false, new RefSpec("HEAD:" + PERSONAL));
        assertEquals(RemoteRefUpdate.Status.OK, create.get(PERSONAL));
        assertEquals(first, fixture.ref(PERSONAL));

        ObjectId second = commit(local, "second");
        Map<String, RemoteRefUpdate.Status> update =
            push(local, remote, false, new RefSpec("HEAD:" + PERSONAL));
        assertEquals(RemoteRefUpdate.Status.OK, update.get(PERSONAL));
        assertEquals(second, fixture.ref(PERSONAL));

        local
            .reset()
            .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
            .setRef(first.name())
            .call();
        assertPushRejected(
            () -> push(local, remote, false, new RefSpec("+HEAD:" + PERSONAL)));
        assertEquals(second, fixture.ref(PERSONAL));

        assertPushRejected(
            () -> push(local, remote, false, new RefSpec(":" + PERSONAL)));
        assertEquals(second, fixture.ref(PERSONAL));

        ObjectId mixedCommit = commit(local, "mixed");
        String allowedMixed = "refs/heads/users/alice/mixed";
        String deniedMixed = "refs/heads/protected/mixed";
        assertPushRejected(
            () ->
                push(
                    local,
                    remote,
                    true,
                    new RefSpec(mixedCommit.name() + ":" + allowedMixed),
                    new RefSpec(mixedCommit.name() + ":" + deniedMixed)));
        assertNull(fixture.ref(allowedMixed));
        assertNull(fixture.ref(deniedMixed));
      }
    }
  }

  @Test
  void missingAndUndiscoverableRepositoriesHaveTheSameNotFoundResponse() throws Exception {
    MutablePolicy policy = MutablePolicy.readable();
    try (SmartHttpFixture fixture = SmartHttpFixture.fetchOnly(policy)) {
      fixture.initializeRepository();
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

      policy.setReadable(false);
      int undiscoverable = status(client, fixture.infoRefsUri("team/demo.git"));

      policy.setReadable(true);
      int missing = status(client, fixture.infoRefsUri("team/missing.git"));

      assertEquals(404, undiscoverable);
      assertEquals(undiscoverable, missing);
    }
  }

  private static int status(HttpClient client, URI uri)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static Git createLocalRepository(Path directory) throws Exception {
    Files.createDirectories(directory);
    Git git = Git.init().setDirectory(directory.toFile()).call();
    Files.writeString(directory.resolve("data.txt"), "first\n", StandardCharsets.UTF_8);
    git.add().addFilepattern("data.txt").call();
    git.commit()
        .setMessage("first")
        .setAuthor("Alice", "alice@example.invalid")
        .setCommitter("Alice", "alice@example.invalid")
        .call();
    return git;
  }

  private static ObjectId commit(Git git, String message) throws Exception {
    Path workTree = git.getRepository().getWorkTree().toPath();
    Files.writeString(
        workTree.resolve("data.txt"), message + "\n", StandardCharsets.UTF_8);
    git.add().addFilepattern("data.txt").call();
    return git.commit()
        .setMessage(message)
        .setAuthor("Alice", "alice@example.invalid")
        .setCommitter("Alice", "alice@example.invalid")
        .call()
        .getId();
  }

  private static Map<String, RemoteRefUpdate.Status> push(
      Git git, String remote, boolean atomic, RefSpec... specifications)
      throws GitAPIException {
    Iterable<PushResult> results =
        git.push()
            .setRemote(remote)
            .setAtomic(atomic)
            .setRefSpecs(specifications)
            .call();
    Map<String, RemoteRefUpdate.Status> statuses = new LinkedHashMap<>();
    for (PushResult result : results) {
      Collection<RemoteRefUpdate> updates = result.getRemoteUpdates();
      for (RemoteRefUpdate update : updates) {
        statuses.put(update.getRemoteName(), update.getStatus());
      }
    }
    return statuses;
  }

  private static void assertPushRejected(PushAttempt attempt) throws Exception {
    try {
      Map<String, RemoteRefUpdate.Status> statuses = attempt.run();
      assertFalse(statuses.isEmpty(), "rejected push must report at least one ref");
      assertTrue(
          statuses.values().stream()
              .anyMatch(
                  status ->
                      status != RemoteRefUpdate.Status.OK
                          && status != RemoteRefUpdate.Status.UP_TO_DATE),
          () -> "expected a rejected remote update but got " + statuses);
    } catch (TransportException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  @FunctionalInterface
  private interface PushAttempt {
    Map<String, RemoteRefUpdate.Status> run() throws Exception;
  }

  private static final class SmartHttpFixture implements AutoCloseable {
    private final HibernateSessionFactoryProvider provider;
    private final DefaultHibernateRepositoryFactory rawFactory;
    private final Server server;
    private final ServerConnector connector;

    private SmartHttpFixture(
        MutablePolicy policy, SmartHttpReceiveAdmission<String> receiveAdmission)
        throws Exception {
      provider = provider();
      SessionFactory sessionFactory = provider.getSessionFactory();
      rawFactory = new DefaultHibernateRepositoryFactory(sessionFactory);
      SecuredHibernateRepositoryFactory<String> securedFactory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);
      GitServlet servlet =
          SecuredSmartHttp.servlet(
              securedFactory,
              ignored -> "alice",
              SmartHttpRepositoryNameMapper.strict(),
              receiveAdmission);

      server = new Server();
      connector = new ServerConnector(server);
      connector.setHost("127.0.0.1");
      connector.setPort(0);
      server.addConnector(connector);
      ServletContextHandler context = new ServletContextHandler();
      context.setContextPath("/");
      context.addServlet(new ServletHolder(servlet), "/git/*");
      server.setHandler(context);
      server.start();
    }

    static SmartHttpFixture fetchOnly(MutablePolicy policy) throws Exception {
      return new SmartHttpFixture(policy, SmartHttpReceiveAdmission.disabled());
    }

    static SmartHttpFixture pushEnabled(MutablePolicy policy) throws Exception {
      return new SmartHttpFixture(
          policy, SmartHttpReceiveAdmission.allowAuthenticatedRequests());
    }

    ObjectId initializeRepository() throws Exception {
      try (HibernateGitStorage storage = rawFactory.open(REPOSITORY)) {
        Repository repository = storage.repository();
        ObjectId commit = insertCommit(repository, null, "initial");
        RefUpdate update = repository.updateRef(MAIN);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(commit);
        assertEquals(RefUpdate.Result.NEW, update.update());
        RefUpdate head = repository.updateRef(Constants.HEAD);
        assertNotEquals(RefUpdate.Result.LOCK_FAILURE, head.link(MAIN));
        return commit;
      }
    }

    ObjectId appendMainCommit(String message) throws Exception {
      try (HibernateGitStorage storage = rawFactory.open(REPOSITORY)) {
        Repository repository = storage.repository();
        Ref current = repository.exactRef(MAIN);
        assertNotNull(current);
        ObjectId next = insertCommit(repository, current.getObjectId(), message);
        RefUpdate update = repository.updateRef(MAIN);
        update.setExpectedOldObjectId(current.getObjectId());
        update.setNewObjectId(next);
        assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
        return next;
      }
    }

    ObjectId ref(String name) throws Exception {
      try (HibernateGitStorage storage = rawFactory.open(REPOSITORY)) {
        Ref ref = storage.repository().exactRef(name);
        return ref == null ? null : ref.getObjectId();
      }
    }

    String remoteUri() {
      return "http://127.0.0.1:" + connector.getLocalPort() + "/git/team/demo.git";
    }

    URI infoRefsUri(String repositoryPath) {
      return URI.create(
          "http://127.0.0.1:"
              + connector.getLocalPort()
              + "/git/"
              + repositoryPath
              + "/info/refs?service=git-upload-pack");
    }

    @Override
    public void close() throws Exception {
      try {
        server.stop();
        server.join();
      } finally {
        provider.close();
      }
    }
  }

  private static ObjectId insertCommit(
      Repository repository, ObjectId parent, String message) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, message.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("data.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent actor = new PersonIdent("Server", "server@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:smart-http-real-client-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static final class MutablePolicy implements RepositoryAccessPolicy<String> {
    private final AtomicBoolean readable = new AtomicBoolean(true);
    private volatile Predicate<RepositoryAccessRequest> writeRule = request -> false;
    private final List<RepositoryAccessRequest> requests = new ArrayList<>();

    static MutablePolicy readable() {
      return new MutablePolicy();
    }

    MutablePolicy allowWrites(Predicate<RepositoryAccessRequest> nextWriteRule) {
      writeRule = Objects.requireNonNull(nextWriteRule, "nextWriteRule");
      return this;
    }

    void setReadable(boolean value) {
      readable.set(value);
    }

    @Override
    public synchronized void require(String context, RepositoryAccessRequest request) {
      assertEquals("alice", context);
      requests.add(request);
      if ((request.operation() == RepositoryAccessOperation.DISCOVER
              || request.operation() == RepositoryAccessOperation.READ)
          && readable.get()) {
        return;
      }
      if (writeRule.test(request)) {
        return;
      }
      throw new RepositoryAccessDeniedException(
          request, "DENIED_BY_REAL_CLIENT_TEST", "real-client-test", 1);
    }
  }
}
