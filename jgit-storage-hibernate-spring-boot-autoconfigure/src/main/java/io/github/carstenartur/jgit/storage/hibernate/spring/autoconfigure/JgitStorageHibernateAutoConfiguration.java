/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionParticipant;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchRepositoryDeletionParticipant;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Creates one dedicated, migration-backed Hibernate persistence context for JGit storage.
 *
 * <p>The auto-configuration deliberately does not register project entities in an application's
 * ordinary JPA persistence unit. Consumers can replace any bean by using the documented bean name or
 * type, while the default path keeps Git schema lifecycle, Hibernate settings and Search ownership
 * isolated from unrelated application entities.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@EnableConfigurationProperties(JgitStorageHibernateProperties.class)
@ConditionalOnClass({DataSource.class, SessionFactory.class, DefaultHibernateRepositoryFactory.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(
    prefix = "jgit.storage.hibernate",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class JgitStorageHibernateAutoConfiguration {

  /** Stable bean name for the dedicated Git persistence context. */
  public static final String SESSION_FACTORY_BEAN = "jgitStorageSessionFactory";

  @Bean(initMethod = "initialize")
  @ConditionalOnMissingBean
  JgitStorageSchemaManager jgitStorageSchemaManager(
      DataSource dataSource, JgitStorageHibernateProperties properties) {
    return new JgitStorageSchemaManager(dataSource, properties);
  }

  @Bean(name = SESSION_FACTORY_BEAN, destroyMethod = "close")
  @ConditionalOnMissingBean(name = SESSION_FACTORY_BEAN)
  SessionFactory jgitStorageSessionFactory(
      DataSource dataSource,
      JgitStorageHibernateProperties properties,
      JgitStorageSchemaManager schemaManager) {
    // The type dependency preserves ordering even when an application supplies the schema manager
    // under a custom bean name. initialize() is idempotent for the default implementation.
    schemaManager.initialize();

    Properties hibernate = new Properties();
    hibernate.put("hibernate.connection.datasource", dataSource);
    hibernate.put("hibernate.hbm2ddl.auto", "validate");
    hibernate.put("hibernate.show_sql", "false");
    hibernate.putAll(properties.getHibernateProperties());

    List<Class<?>> additionalEntities = new ArrayList<>();
    if (properties.getSearch().isEnabled()) {
      additionalEntities.addAll(SearchEntities.annotatedClasses());
      hibernate.putIfAbsent("hibernate.search.backend.type", "lucene");
      hibernate.putIfAbsent("hibernate.search.backend.directory.type", "local-filesystem");
      hibernate.putIfAbsent(
          "hibernate.search.backend.directory.root", properties.getSearch().getDirectory());
    }
    return new HibernateSessionFactoryProvider(hibernate, additionalEntities).getSessionFactory();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "jgit.storage.hibernate.search",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean
  SearchRepositoryDeletionParticipant jgitSearchRepositoryDeletionParticipant() {
    return new SearchRepositoryDeletionParticipant();
  }

  @Bean
  @ConditionalOnMissingBean
  DefaultHibernateRepositoryFactory jgitHibernateRepositoryFactory(
      @Qualifier(SESSION_FACTORY_BEAN) SessionFactory sessionFactory,
      ObjectProvider<SearchRepositoryDeletionParticipant> searchDeletionParticipant) {
    List<RepositoryDeletionParticipant> participants =
        searchDeletionParticipant.orderedStream()
            .map(RepositoryDeletionParticipant.class::cast)
            .toList();
    return new DefaultHibernateRepositoryFactory(sessionFactory, participants);
  }

  @Bean
  @ConditionalOnMissingBean
  JgitRepositoryService jgitRepositoryService(
      DefaultHibernateRepositoryFactory repositoryFactory,
      @Qualifier(SESSION_FACTORY_BEAN) SessionFactory sessionFactory) {
    return new JgitRepositoryService(repositoryFactory, sessionFactory);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "jgit.storage.hibernate.search",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean
  GitHistorySearchService jgitHistorySearchService(
      @Qualifier(SESSION_FACTORY_BEAN) SessionFactory sessionFactory) {
    return new GitHistorySearchService(sessionFactory);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "jgit.storage.hibernate.search",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean
  CommitProjectionRebuilder jgitCommitProjectionRebuilder(
      @Qualifier(SESSION_FACTORY_BEAN) SessionFactory sessionFactory) {
    return new CommitProjectionRebuilder(sessionFactory);
  }

  @Bean
  @ConditionalOnMissingBean(name = "jgitConfiguredRepositoryBootstrap")
  ApplicationRunner jgitConfiguredRepositoryBootstrap(
      JgitRepositoryService repositories, JgitStorageHibernateProperties properties) {
    return ignored -> {
      for (String repository : properties.getRepositories()) {
        repositories.create(repository);
      }
    };
  }
}
