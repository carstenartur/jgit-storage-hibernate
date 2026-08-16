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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessPolicy;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecuredSmartHttpAdapterH2Test {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName REPOSITORY = new RepositoryName("team/demo");

  @Test
  void resolverFactoriesAndJgitCloseShareOneBoundContext() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("pipeline")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      initializeRepository(sessionFactory);
      RecordingPolicy policy = new RecordingPolicy();
      policy.allow(RepositoryAccessOperation.DISCOVER, RepositoryAccessOperation.READ);
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);
      HttpServletRequest request = request();
      AtomicReference<String> admitted = new AtomicReference<>();

      SecuredSmartHttpRepositoryResolver<String> resolver =
          new SecuredSmartHttpRepositoryResolver<>(factory, ignored -> "alice");
      Repository repository = resolver.open(request, "team/demo.git");
      assertNotNull(repository.exactRef("refs/heads/main"));

      UploadPack upload = new SecuredSmartHttpUploadPackFactory<String>().create(request, repository);
      assertSame(repository, upload.getRepository());

      ReceivePack receive =
          new SecuredSmartHttpReceivePackFactory<String>(
                  (ignored, repositoryName, context) ->
                      admitted.set(repositoryName.value() + ":" + context))
              .create(request, repository);
      assertSame(repository, receive.getRepository());
      assertTrue(receive.isAtomic());
      assertEquals("team/demo:alice", admitted.get());
      assertEquals(
          List.of(
              RepositoryAccessOperation.DISCOVER,
              RepositoryAccessOperation.READ,
              RepositoryAccessOperation.READ,
              RepositoryAccessOperation.READ),
          policy.operations());

      repository.close();
      RepositoryDeletionResult deletion =
          new DefaultHibernateRepositoryFactory(sessionFactory).deleteRepository(REPOSITORY);
      assertTrue(deletion.deletedAnything());
    }
  }

  @Test
  void resolverConcealsDeniedAndMissingRepositories() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("conceal")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      initializeRepository(sessionFactory);
      RecordingPolicy policy = new RecordingPolicy();
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);
      SecuredSmartHttpRepositoryResolver<String> resolver =
          new SecuredSmartHttpRepositoryResolver<>(factory, ignored -> "alice");

      assertThrows(
          RepositoryNotFoundException.class,
          () -> resolver.open(request(), "team/demo.git"));

      policy.allow(RepositoryAccessOperation.DISCOVER, RepositoryAccessOperation.READ);
      assertThrows(
          RepositoryNotFoundException.class,
          () -> resolver.open(request(), "team/missing.git"));
    }
  }

  @Test
  void missingAuthenticationAndMissingRequestBindingFailClosed() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("binding")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      initializeRepository(sessionFactory);
      RecordingPolicy policy = new RecordingPolicy();
      policy.allow(RepositoryAccessOperation.DISCOVER, RepositoryAccessOperation.READ);
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, policy);

      SecuredSmartHttpRepositoryResolver<String> unauthenticated =
          new SecuredSmartHttpRepositoryResolver<>(
              factory,
              ignored -> {
                throw new ServiceNotAuthorizedException();
              });
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> unauthenticated.open(request(), "team/demo.git"));

      HttpServletRequest boundRequest = request();
      Repository repository =
          new SecuredSmartHttpRepositoryResolver<>(factory, ignored -> "alice")
              .open(boundRequest, "team/demo.git");
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> new SecuredSmartHttpUploadPackFactory<String>().create(request(), repository));
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> new SecuredSmartHttpReceivePackFactory<String>().create(request(), repository));
      repository.close();
    }
  }

  private static ObjectId initializeRepository(SessionFactory sessionFactory) throws Exception {
    try (HibernateGitStorage storage =
        new DefaultHibernateRepositoryFactory(sessionFactory).open(REPOSITORY)) {
      Repository repository = storage.repository();
      ObjectId initial = insertCommit(repository, "initial");
      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(initial);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return initial;
    }
  }

  private static ObjectId insertCommit(Repository repository, String message) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, message.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("data.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent actor = new PersonIdent("Smart HTTP Test", "smart-http@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:smart-http-"
            + purpose
            + "-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static HttpServletRequest request() {
    RequestInvocationHandler handler = new RequestInvocationHandler();
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            handler);
  }

  private static final class RequestInvocationHandler implements InvocationHandler {
    private final Map<String, Object> attributes = new HashMap<>();

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) {
      return switch (method.getName()) {
        case "getAttribute" -> attributes.get(arguments[0]);
        case "setAttribute" -> {
          if (arguments[1] == null) {
            attributes.remove(arguments[0]);
          } else {
            attributes.put((String) arguments[0], arguments[1]);
          }
          yield null;
        }
        case "removeAttribute" -> {
          attributes.remove(arguments[0]);
          yield null;
        }
        case "isSecure" -> true;
        case "toString" -> "SmartHttpTestRequest";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == arguments[0];
        default -> defaultValue(method.getReturnType());
      };
    }

    private static Object defaultValue(Class<?> type) {
      if (!type.isPrimitive()) {
        return null;
      }
      if (type == boolean.class) {
        return false;
      }
      if (type == char.class) {
        return '\0';
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
      throw new IllegalArgumentException("Unsupported primitive return type: " + type);
    }
  }

  private static final class RecordingPolicy implements RepositoryAccessPolicy<String> {
    private final Set<RepositoryAccessOperation> allowed =
        EnumSet.noneOf(RepositoryAccessOperation.class);
    private final List<RepositoryAccessRequest> requests = new ArrayList<>();

    void allow(RepositoryAccessOperation... operations) {
      allowed.addAll(List.of(operations));
    }

    List<RepositoryAccessOperation> operations() {
      return requests.stream().map(RepositoryAccessRequest::operation).toList();
    }

    @Override
    public void require(String context, RepositoryAccessRequest request) {
      assertEquals("alice", context);
      requests.add(request);
      if (!allowed.contains(request.operation())) {
        throw new RepositoryAccessDeniedException(
            request, "DENIED_BY_SMART_HTTP_TEST", "smart-http-test", 1);
      }
    }
  }
}
