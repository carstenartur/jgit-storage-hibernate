/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessAuditEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityIdentityAuditEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityLocalCredentialEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityVersionEntity;
import java.util.List;

/** Stable registration contract for the Hibernate entities owned by the Security capability. */
public final class SecurityEntities {

  private static final List<Class<?>> ANNOTATED_CLASSES =
      List.of(
          SecurityPrincipalEntity.class,
          SecurityGroupEntity.class,
          SecurityGroupMembershipEntity.class,
          SecurityRepositoryGrantEntity.class,
          SecurityRefRuleEntity.class,
          SecurityVersionEntity.class,
          SecurityAccessAuditEntity.class,
          SecurityLocalCredentialEntity.class,
          SecurityAccessTokenEntity.class,
          SecurityIdentityAuditEntity.class);

  private SecurityEntities() {}

  /** Returns the immutable set of Security-owned annotated entity classes. */
  public static List<Class<?>> annotatedClasses() {
    return ANNOTATED_CLASSES;
  }
}
