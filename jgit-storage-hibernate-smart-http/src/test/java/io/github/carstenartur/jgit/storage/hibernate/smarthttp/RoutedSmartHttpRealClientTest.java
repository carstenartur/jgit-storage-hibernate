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

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessPolicy;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.AccessTokenHash;
import io.github.carstenartur.jgit.storage.hibernate.security.AccessTokenHasher;
import io.github.carstenartur.jgit.storage.hibernate.security.AuthenticatedGitAccess;
import io.github.carstenartur.jgit.storage.hibernate.security.GitAccessContext;
import io.github.carstenartur.jgit.storage.hibernate.security.GitRepositoryPermission;
import io.github.carstenartur.jgit.storage.hibernate.security.HibernateSecurityCredentialService;
import io.github.carstenartur.jgit.storage.hibernate.security.HibernateSecurityIdentityAuditService;
import io.github.carstenartur.jgit.storage.hibernate.security.IssuedAccessToken;
import io.github.carstenartur.jgit.storage.hibernate.security.PasswordHash;
import io.github.carstenartur.jgit.storage.hibernate.security.PasswordHasher;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAccessTokenNamespace;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationTrace;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityCredentialKind;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityEntities;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityManagementRequest;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.security.SecuritySmartHttpAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
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
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class RoutedSmartHttpRealClientTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName REPOSITORY = new RepositoryName("team/routed");
  private static final String MAIN = "refs/heads/main";
  private static final Instant START = Instant.parse("2026-08-18T08:00:00Z");
  private static final GitAccessContext ADMIN =
      new GitAccessContext(
          "admin", Set.of(), "oidc", "admin-session", "admin-correlation", Map.of());

  @TempDir Path temporaryDirectory;

  @Test
  void realClientsCloneFetchAndPushThroughExternalAndLocalPatRoutes() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ObjectId initial = fixture.initializeRepository();
      String remote = fixture.remoteUri();

      try (Git external =
              cloneWithBearer(
                  remote,
                  fixture.externalBearer(),
                  temporaryDirectory.resolve("external-clone"));
          Git local =
              cloneWithBearer(
                  remote,
                  fixture.localToken().tokenValue(),
                  temporaryDirectory.resolve("local-clone"))) {
        assertEquals(initial, external.getRepository().resolve(MAIN));
        assertEquals(initial, local.getRepository().resolve(MAIN));

        ObjectId serverUpdate = fixture.appendMainCommit("server-update");
        fetchWithBearer(external, fixture.externalBearer());
        fetchWithBearer(local, fixture.localToken().tokenValue());
        assertEquals(serverUpdate, external.getRepository().resolve("refs/remotes/origin/main"));
        assertEquals(serverUpdate, local.getRepository().resolve("refs/remotes/origin/main"));

        ObjectId externalCommit = commit(external, "external-push");
        String externalRef = "refs/heads/external/topic";
        pushWithBearer(external, fixture.externalBearer(), externalRef);
        assertEquals(externalCommit, fixture.ref(externalRef));

        ObjectId localCommit = commit(local, "local-push");
        String localRef = "refs/heads/local/topic";
        pushWithBearer(local, fixture.localToken().tokenValue(), localRef);
        assertEquals(localCommit, fixture.ref(localRef));

        fixture.revokeLocalToken();
        assertThrows(
            TransportException.class,
            () -> fetchWithBearer(local, fixture.localToken().tokenValue()));
        fetchWithBearer(external, fixture.externalBearer());
      }

      assertTrue(fixture.policy().observedPrincipals().contains("external-user"));
      assertTrue(fixture.policy().observedPrincipals().contains("alice"));
      assertTrue(fixture.policy().observedOperations().contains(RepositoryAccessOperation.READ));
      assertTrue(
          fixture.policy().observedOperations().contains(RepositoryAccessOperation.CREATE_REF));
    }
  }

  private static Git cloneWithBearer(String remote, String bearer, Path directory)
      throws Exception {
    return Git.cloneRepository()
        .setURI(remote)
        .setDirectory(directory.toFile())
        .setBranch(MAIN)
        .setTransportConfigCallback(bearer(bearer))
        .call();
  }

  private static void fetchWithBearer(Git git, String bearer) throws Exception {
    git.fetch()
        .setRemote("origin")
        .setTransportConfigCallback(bearer(bearer))
        .call();
  }

  private static void pushWithBearer(Git git, String bearer, String targetRef)
      throws Exception {
    git.push()
        .setRemote("origin")
        .setRefSpecs(new RefSpec("HEAD:" + targetRef))
        .setTransportConfigCallback(bearer(bearer))
        .call();
  }

  private static TransportConfigCallback bearer(String token) {
    return transport -> {
      if (!(transport instanceof TransportHttp http)) {
        throw new IllegalStateException("Expected JGit TransportHttp");
      }
      http.setAdditionalHeaders(Map.of("Authorization", "Bearer " + token));
    };
  }

  private static ObjectId commit(Git git, String message) throws Exception {
    Path workTree = git.getRepository().getWorkTree().toPath();
    Files.writeString(
        workTree.resolve("data.txt"), message + "\n", StandardCharsets.UTF_8);
    git.add().addFilepattern("data.txt").call();
    return git.commit()
        .setMessage(message)
        .setAuthor("Client", "client@example.invalid")
        .setCommitter("Client", "client@example.invalid")
        .call()
        .getId();
  }

  private static final class Fixture implements AutoCloseable {

    private static final String EXTERNAL_BEARER = "external.jwt.value";

    private final HibernateSessionFactoryProvider provider;
    private final SessionFactory sessionFactory;
    private final DefaultHibernateRepositoryFactory rawFactory;
    private final HibernateSecurityCredentialService credentials;
    private final IssuedAccessToken localToken;
    private final RecordingPolicy policy;
    private final Server server;
    private final ServerConnector connector;

    Fixture() throws Exception {
      provider = provider();
      sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      credentials = credentials(sessionFactory);
      localToken =
          credentials.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(
                  GitRepositoryPermission.DISCOVER,
                  GitRepositoryPermission.READ,
                  GitRepositoryPermission.CREATE_REF,
                  GitRepositoryPermission.UPDATE_REF),
              Instant.now().plus(Duration.ofHours(1)));
      assertTrue(SecurityAccessTokenNamespace.isVersion1Token(localToken.tokenValue()));

      AuthenticatedGitAccess externalAccess =
          AuthenticatedGitAccess.unrestricted(
              new GitAccessContext(
                  "external-user",
                  Set.of("developers"),
                  "oidc",
                  "external-session",
                  "external-correlation",
                  Map.of()),
              SecurityCredentialKind.EXTERNAL,
              "oidc:external-user",
              1);
      RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
          SecuritySmartHttpAuthentication.externalBearerAndAccessToken(
              credentials,
              request ->
                  SecurityAuthenticationTrace.withoutRemoteAddress(
                      "protocol-session", "protocol-correlation"),
              "oidc",
              (request, credential) -> {
                if (!EXTERNAL_BEARER.equals(credential)) {
                  throw new org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException();
                }
                return externalAccess;
              },
              SmartHttpChallengeOwner.APPLICATION,
              ignored -> true);

      policy = new RecordingPolicy();
      rawFactory = new DefaultHibernateRepositoryFactory(sessionFactory);
      SecuredHibernateRepositoryFactory<AuthenticatedGitAccess> securedFactory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);
      GitServlet servlet =
          SecuredSmartHttp.servlet(
              securedFactory,
              authentication,
              SmartHttpRepositoryNameMapper.strict(),
              SmartHttpReceiveAdmission.allowAuthenticatedRequests());

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

    ObjectId initializeRepository() throws Exception {
      try (HibernateGitStorage storage = rawFactory.open(REPOSITORY)) {
        Repository repository = storage.repository();
        ObjectId commit = insertCommit(repository, null, "initial");
        RefUpdate update = repository.updateRef(MAIN);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(commit);
        assertEquals(RefUpdate.Result.NEW, update.update());
        RefUpdate head = repository.updateRef(Constants.HEAD);
        head.link(MAIN);
        return commit;
      }
    }

    ObjectId appendMainCommit(String message) throws Exception {
      try (HibernateGitStorage storage = rawFactory.open(REPOSITORY)) {
        Repository repository = storage.repository();
        Ref current = repository.exactRef(MAIN);
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

    void revokeLocalToken() {
      credentials.revokeAccessToken(
          SecurityManagementRequest.revokeToken(
              ADMIN, "alice", localToken.metadata().tokenId()));
    }

    String externalBearer() {
      return EXTERNAL_BEARER;
    }

    IssuedAccessToken localToken() {
      return localToken;
    }

    RecordingPolicy policy() {
      return policy;
    }

    String remoteUri() {
      return "http://127.0.0.1:"
          + connector.getLocalPort()
          + "/git/team/routed.git";
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

  private static final class RecordingPolicy
      implements RepositoryAccessPolicy<AuthenticatedGitAccess> {

    private final Set<String> observedPrincipals =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<RepositoryAccessOperation> observedOperations =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void require(
        AuthenticatedGitAccess access, RepositoryAccessRequest request) {
      observedPrincipals.add(access.context().principalId());
      observedOperations.add(request.operation());
      GitRepositoryPermission permission = permission(request.operation());
      if (Set.of("alice", "external-user").contains(access.context().principalId())
          && access.carries(permission)) {
        return;
      }
      throw new RepositoryAccessDeniedException(
          request, "DENIED_BY_ROUTED_CLIENT_TEST", "routed-client-test", 1);
    }

    Set<String> observedPrincipals() {
      return Set.copyOf(observedPrincipals);
    }

    Set<RepositoryAccessOperation> observedOperations() {
      return Set.copyOf(observedOperations);
    }

    private static GitRepositoryPermission permission(
        RepositoryAccessOperation operation) {
      return switch (operation) {
        case DISCOVER -> GitRepositoryPermission.DISCOVER;
        case READ -> GitRepositoryPermission.READ;
        case CREATE_REF -> GitRepositoryPermission.CREATE_REF;
        case UPDATE_REF -> GitRepositoryPermission.UPDATE_REF;
        case DELETE_REF -> GitRepositoryPermission.DELETE_REF;
        case FORCE_UPDATE -> GitRepositoryPermission.FORCE_UPDATE;
        case DELETE_REPOSITORY -> GitRepositoryPermission.ADMINISTER;
      };
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

  private static HibernateSecurityCredentialService credentials(
      SessionFactory sessionFactory) {
    return new HibernateSecurityCredentialService(
        sessionFactory,
        new TestPasswordHasher(),
        new TestTokenHasher(),
        request -> {},
        new HibernateSecurityIdentityAuditService(sessionFactory));
  }

  private static void persistPrincipal(
      SessionFactory sessionFactory, String principalId, String loginName) {
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId(principalId);
    principal.setPrincipalType(SecurityPrincipalType.USER);
    principal.setLoginName(loginName);
    principal.setDisplayName(principalId);
    principal.setStatus(SecurityPrincipalStatus.ACTIVE);
    principal.setCreatedAt(START);
    principal.setUpdatedAt(START);
    principal.setSecurityVersion(1);
    sessionFactory.inTransaction(session -> session.persist(principal));
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:routed-smart-http-client-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(
        properties, SecurityEntities.annotatedClasses());
  }

  private static final class TestPasswordHasher implements PasswordHasher {

    @Override
    public PasswordHash hash(char[] password) {
      return new PasswordHash("TEST-PASSWORD", 1, "value:" + new String(password));
    }

    @Override
    public boolean verify(char[] password, PasswordHash expected) {
      return expected.encodedHash().equals("value:" + new String(password));
    }

    @Override
    public boolean needsRehash(PasswordHash existing) {
      return false;
    }
  }

  private static final class TestTokenHasher implements AccessTokenHasher {

    @Override
    public AccessTokenHash hash(String tokenValue) {
      return new AccessTokenHash("TEST-TOKEN", 1, "value:" + tokenValue);
    }

    @Override
    public boolean verify(String tokenValue, AccessTokenHash expected) {
      return expected.encodedHash().equals("value:" + tokenValue);
    }
  }
}
