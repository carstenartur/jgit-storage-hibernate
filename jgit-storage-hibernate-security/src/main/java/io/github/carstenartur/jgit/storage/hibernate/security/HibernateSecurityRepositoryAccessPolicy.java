/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessPolicy;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Database-backed Core access policy that reloads current principal, membership and ACL state.
 *
 * <p>The caller-supplied principal ID remains the authentication identity. Group IDs from the
 * caller are deliberately ignored and rebuilt from active database memberships on every check, so
 * a long-lived repository session does not indefinitely retain a removed group grant.
 */
public final class HibernateSecurityRepositoryAccessPolicy
    implements RepositoryAccessPolicy<GitAccessContext> {

  private final SessionFactory sessionFactory;
  private final SecurityAccessAuditRecorder auditRecorder;

  /**
   * Create a database-backed policy without persistent audit.
   *
   * @param sessionFactory Hibernate session factory containing Security entities
   */
  public HibernateSecurityRepositoryAccessPolicy(SessionFactory sessionFactory) {
    this(sessionFactory, SecurityAccessAuditRecorder.NONE);
  }

  /**
   * Create a database-backed policy that audits every allowed, denied or failed evaluation.
   *
   * @param sessionFactory Hibernate session factory containing Security entities
   * @param auditRecorder authorization audit sink
   */
  public HibernateSecurityRepositoryAccessPolicy(
      SessionFactory sessionFactory, SecurityAccessAuditRecorder auditRecorder) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
  }

  @Override
  public void require(GitAccessContext context, RepositoryAccessRequest request) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");
    boolean delegatedToDecisionPolicy = false;
    try (Session session = sessionFactory.openSession()) {
      SecurityPrincipalEntity principal =
          session.find(SecurityPrincipalEntity.class, context.principalId());
      if (principal == null || principal.getStatus() != SecurityPrincipalStatus.ACTIVE) {
        long version = principal != null ? principal.getSecurityVersion() : 0L;
        RepositoryAccessDeniedException denied =
            new RepositoryAccessDeniedException(
                request, "PRINCIPAL_NOT_ACTIVE", null, version);
        SecurityAuditSupport.deny(
            auditRecorder, SecurityAccessAuditRecord.denied(context, denied), denied);
        return;
      }

      List<SecurityGroupMembershipEntity> memberships =
          session
              .createSelectionQuery(
                  "FROM SecurityGroupMembership m WHERE m.principalId = :principalId",
                  SecurityGroupMembershipEntity.class)
              .setParameter("principalId", context.principalId())
              .getResultList();
      Set<String> membershipGroupIds = new HashSet<>();
      memberships.forEach(membership -> membershipGroupIds.add(membership.getGroupId()));

      Set<String> activeGroupIds = new HashSet<>();
      if (!membershipGroupIds.isEmpty()) {
        List<SecurityGroupEntity> groups =
            session
                .createSelectionQuery(
                    "FROM SecurityGroup g WHERE g.groupId IN :groupIds AND g.status = :status",
                    SecurityGroupEntity.class)
                .setParameter("groupIds", membershipGroupIds)
                .setParameter("status", SecurityGroupStatus.ACTIVE)
                .getResultList();
        groups.forEach(group -> activeGroupIds.add(group.getGroupId()));
      }

      List<RepositoryGrant> grants =
          session
              .createSelectionQuery(
                  "FROM SecurityRepositoryGrant g WHERE g.repositoryName = :repositoryName",
                  SecurityRepositoryGrantEntity.class)
              .setParameter("repositoryName", request.repositoryName().value())
              .getResultList()
              .stream()
              .map(HibernateSecurityRepositoryAccessPolicy::toGrant)
              .toList();
      List<RepositoryRefRule> refRules =
          session
              .createSelectionQuery(
                  "FROM SecurityRefRule r WHERE r.repositoryName = :repositoryName "
                      + "AND r.enabled = true",
                  SecurityRefRuleEntity.class)
              .setParameter("repositoryName", request.repositoryName().value())
              .getResultList()
              .stream()
              .map(HibernateSecurityRepositoryAccessPolicy::toRule)
              .toList();

      GitAccessContext effectiveContext =
          new GitAccessContext(
              context.principalId(),
              activeGroupIds,
              context.authenticationMethod(),
              context.sessionId(),
              context.correlationId(),
              context.attributes());
      SecurityRepositoryAccessPolicy decisionPolicy =
          new SecurityRepositoryAccessPolicy(
              new SecurityAuthorizationEvaluator(grants, refRules), auditRecorder);
      delegatedToDecisionPolicy = true;
      decisionPolicy.require(effectiveContext, request);
    } catch (RepositoryAccessDeniedException | SecurityAuditPersistenceException handled) {
      throw handled;
    } catch (RuntimeException failure) {
      if (delegatedToDecisionPolicy) {
        // The delegated policy has already recorded FAILED or attached its audit failure.
        throw failure;
      }
      SecurityAuditSupport.fail(
          auditRecorder,
          SecurityAccessAuditRecord.failed(context, request, failure),
          failure);
    }
  }

  private static RepositoryGrant toGrant(SecurityRepositoryGrantEntity entity) {
    return new RepositoryGrant(
        entity.getGrantId(),
        new SecuritySubject(entity.getSubjectType(), entity.getSubjectId()),
        new io.github.carstenartur.jgit.storage.hibernate.RepositoryName(
            entity.getRepositoryName()),
        entity.getPermission(),
        entity.getEffect(),
        entity.getSecurityVersion());
  }

  private static RepositoryRefRule toRule(SecurityRefRuleEntity entity) {
    SecuritySubject subject = null;
    if (entity.getSubjectType() != null || entity.getSubjectId() != null) {
      if (entity.getSubjectType() == null || entity.getSubjectId() == null) {
        throw new SecurityPolicyConfigurationException(
            "ref rule subject type and id must either both be null or both be present");
      }
      subject = new SecuritySubject(entity.getSubjectType(), entity.getSubjectId());
    }
    return new RepositoryRefRule(
        entity.getRuleId(),
        new io.github.carstenartur.jgit.storage.hibernate.RepositoryName(
            entity.getRepositoryName()),
        entity.getRefPattern(),
        entity.getPermission(),
        entity.getEffect(),
        entity.getPriority(),
        subject,
        entity.getSecurityVersion());
  }
}
