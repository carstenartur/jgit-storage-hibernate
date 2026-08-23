/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionParticipant;
import io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchRepositoryDeletionParticipant;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SecuredSmartHttp;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpAccessContextProvider;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpPostReceiveHandler;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpReceiveAdmission;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpRepositoryNameMapper;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure.JgitStorageHibernateAutoConfiguration;
import java.security.Principal;
import java.util.List;
import javax.sql.DataSource;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Standalone HTTP authentication, Smart HTTP and bounded projection wiring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JgitStorageServerProperties.class)
public class ServerRuntimeConfiguration {

  @Bean
  FilterRegistrationBean<ServerBasicAuthenticationFilter> serverAuthenticationFilter(
      JgitStorageServerProperties properties) {
    FilterRegistrationBean<ServerBasicAuthenticationFilter> registration =
        new FilterRegistrationBean<>(
            new ServerBasicAuthenticationFilter(properties.getAuthentication()));
    registration.setName("jgitStorageServerAuthentication");
    registration.addUrlPatterns("/git/*", "/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean(name = "jgitProjectionExecutor")
  ThreadPoolTaskExecutor jgitProjectionExecutor(JgitStorageServerProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.getIndexingThreads());
    executor.setMaxPoolSize(properties.getIndexingThreads());
    executor.setQueueCapacity(64);
    executor.setThreadNamePrefix("jgit-projection-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }

  @Bean
  RepositoryProjectionScheduler repositoryProjectionScheduler(
      JgitRepositoryService repositories,
      CommitProjectionRebuilder rebuilder,
      @Qualifier("jgitProjectionExecutor") ThreadPoolTaskExecutor executor) {
    return new RepositoryProjectionScheduler(repositories, rebuilder, executor);
  }

  @Bean
  SecuredHibernateRepositoryFactory<ServerPrincipal> serverSecuredRepositoryFactory(
      @Qualifier(JgitStorageHibernateAutoConfiguration.SESSION_FACTORY_BEAN)
          SessionFactory sessionFactory,
      ObjectProvider<SearchRepositoryDeletionParticipant> deletionParticipant) {
    List<RepositoryDeletionParticipant> participants =
        deletionParticipant.orderedStream()
            .map(RepositoryDeletionParticipant.class::cast)
            .toList();
    return new SecuredHibernateRepositoryFactory<>(
        sessionFactory, participants, (principal, request) -> {});
  }

  @Bean
  SmartHttpAccessContextProvider<ServerPrincipal> serverSmartHttpAccessContextProvider() {
    return request -> {
      Principal principal = request.getUserPrincipal();
      if (principal instanceof ServerPrincipal serverPrincipal) {
        return serverPrincipal;
      }
      throw new org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException();
    };
  }

  @Bean
  SmartHttpPostReceiveHandler<ServerPrincipal> serverPostReceiveHandler(
      RepositoryProjectionScheduler scheduler) {
    return (request, repositoryName, accessContext, receivePack, commands) -> {
      boolean changed =
          commands.stream().anyMatch(command -> command.getResult() == ReceiveCommand.Result.OK);
      if (changed) {
        scheduler.schedule(repositoryName.value());
      }
    };
  }

  @Bean
  ServletRegistrationBean<GitServlet> gitSmartHttpServlet(
      SecuredHibernateRepositoryFactory<ServerPrincipal> repositoryFactory,
      SmartHttpAccessContextProvider<ServerPrincipal> accessContextProvider,
      SmartHttpPostReceiveHandler<ServerPrincipal> postReceiveHandler) {
    GitServlet servlet =
        SecuredSmartHttp.servlet(
            repositoryFactory,
            accessContextProvider,
            SmartHttpRepositoryNameMapper.strict(),
            SmartHttpReceiveAdmission.allowAuthenticatedRequests(),
            postReceiveHandler);
    ServletRegistrationBean<GitServlet> registration =
        new ServletRegistrationBean<>(servlet, "/git/*");
    registration.setName("jgitStorageSmartHttp");
    registration.setLoadOnStartup(1);
    registration.setAsyncSupported(true);
    return registration;
  }

  @Bean
  PostgresInspectionViewInitializer postgresInspectionViewInitializer(
      DataSource dataSource, JgitStorageServerProperties properties) {
    return new PostgresInspectionViewInitializer(dataSource, properties);
  }
}
