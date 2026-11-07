/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.util.Objects;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.cfg.HibernateOrmMapperSettings;
import org.hibernate.search.mapper.orm.mapping.HibernateOrmMappingConfigurationContext;
import org.hibernate.search.mapper.orm.mapping.HibernateOrmSearchMappingConfigurer;
import org.hibernate.search.mapper.pojo.bridge.RoutingBridge;
import org.hibernate.search.mapper.pojo.bridge.runtime.RoutingBridgeRouteContext;
import org.hibernate.search.mapper.pojo.route.DocumentRoutes;

/** Opt-in mapping for repository-routed commit indexing and search. */
public final class RepositoryRoutingMappingConfigurer
    implements HibernateOrmSearchMappingConfigurer {

  /** Dedicated index name; existing unrouted documents cannot be reused with this mapping. */
  public static final String INDEX_NAME = "GitCommitIndexByRepository";

  @Override
  public void configure(HibernateOrmMappingConfigurationContext context) {
    context
        .programmaticMapping()
        .type(GitCommitIndex.class)
        .indexed()
        .index(INDEX_NAME)
        .routingBinder(
            binding -> {
              binding.dependencies().use("repositoryName");
              binding.bridge(GitCommitIndex.class, new RepositoryRoute());
            });
  }

  /** Return whether the session factory uses this opt-in commit index mapping. */
  public static boolean isEnabled(SessionFactory sessionFactory) {
    var properties = sessionFactory.getProperties();
    if (!properties.containsKey(HibernateOrmMapperSettings.MAPPING_CONFIGURER)
        || !Boolean.parseBoolean(
            Objects.toString(properties.get(HibernateOrmMapperSettings.ENABLED), "true"))) {
      return false;
    }
    return INDEX_NAME.equals(
        Search.mapping(sessionFactory)
            .indexedEntity(GitCommitIndex.class)
            .indexManager()
            .descriptor()
            .hibernateSearchName());
  }

  private static final class RepositoryRoute implements RoutingBridge<GitCommitIndex> {

    @Override
    public void route(
        DocumentRoutes routes,
        Object entityIdentifier,
        GitCommitIndex projection,
        RoutingBridgeRouteContext context) {
      routes.addRoute().routingKey(projection.getRepositoryName());
    }

    @Override
    public void previousRoutes(
        DocumentRoutes routes,
        Object entityIdentifier,
        GitCommitIndex projection,
        RoutingBridgeRouteContext context) {
      route(routes, entityIdentifier, projection, context);
    }
  }
}
