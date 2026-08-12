/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecurityRepositoryAccessPolicyTest {

  private static final RepositoryName REPOSITORY = new RepositoryName("workflows");
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of("contributors"), "oidc", "session", "correlation", Map.of());
  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

  @Test
  void adapterMapsEveryCoreOperationAndPreservesDenialEvidence() {
    Map<RepositoryAccessOperation, GitRepositoryPermission> mappings =
        new EnumMap<>(RepositoryAccessOperation.class);
    mappings.put(RepositoryAccessOperation.DISCOVER, GitRepositoryPermission.DISCOVER);
    mappings.put(RepositoryAccessOperation.READ, GitRepositoryPermission.READ);
    mappings.put(RepositoryAccessOperation.CREATE_REF, GitRepositoryPermission.CREATE_REF);
    mappings.put(RepositoryAccessOperation.UPDATE_REF, GitRepositoryPermission.UPDATE_REF);
    mappings.put(RepositoryAccessOperation.DELETE_REF, GitRepositoryPermission.DELETE_REF);
    mappings.put(RepositoryAccessOperation.FORCE_UPDATE, GitRepositoryPermission.FORCE_UPDATE);
    mappings.put(RepositoryAccessOperation.DELETE_REPOSITORY, GitRepositoryPermission.ADMINISTER);

    for (Map.Entry<RepositoryAccessOperation, GitRepositoryPermission> mapping :
        mappings.entrySet()) {
      RepositoryGrant grant =
          new RepositoryGrant(
              "grant-" + mapping.getKey().name().toLowerCase(),
              SecuritySubject.principal("alice"),
              REPOSITORY,
              mapping.getValue(),
              SecurityEffect.ALLOW,
              4);
      SecurityRepositoryAccessPolicy policy =
          new SecurityRepositoryAccessPolicy(
              new SecurityAuthorizationEvaluator(List.of(grant), List.of()));
      RepositoryAccessRequest request = request(mapping.getKey());
      assertDoesNotThrow(() -> policy.require(ALICE, request), mapping.getKey().name());
    }

    RepositoryGrant deny =
        new RepositoryGrant(
            "deny-read",
            SecuritySubject.principal("alice"),
            REPOSITORY,
            GitRepositoryPermission.READ,
            SecurityEffect.DENY,
            9);
    RepositoryAccessDeniedException denied =
        assertThrows(
            RepositoryAccessDeniedException.class,
            () ->
                new SecurityRepositoryAccessPolicy(
                        new SecurityAuthorizationEvaluator(List.of(deny), List.of()))
                    .require(
                        ALICE,
                        RepositoryAccessRequest.repository(
                            REPOSITORY, RepositoryAccessOperation.READ)));
    assertEquals("EXPLICIT_GRANT_DENY", denied.reasonCode());
    assertEquals("deny-read", denied.evidenceId());
    assertEquals(9, denied.policyVersion());
  }

  @Test
  void hibernatePolicyReloadsMembershipPrincipalAndRefRuleState() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPolicy(sessionFactory);
      HibernateSecurityRepositoryAccessPolicy policy =
          new HibernateSecurityRepositoryAccessPolicy(sessionFactory);
      GitAccessContext contextWithStaleGroups =
          new GitAccessContext(
              "alice",
              Set.of("contributors", "forged-client-group"),
              "oidc",
              "session",
              "correlation",
              Map.of());

      assertDoesNotThrow(
          () ->
              policy.require(
                  contextWithStaleGroups,
                  RepositoryAccessRequest.repository(
                      REPOSITORY, RepositoryAccessOperation.READ)));
      RepositoryAccessDeniedException protectedRef =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () ->
                  policy.require(
                      contextWithStaleGroups,
                      RepositoryAccessRequest.ref(
                          REPOSITORY,
                          RepositoryAccessOperation.UPDATE_REF,
                          "refs/heads/main",
                          null,
                          null)));
      assertEquals("PROTECTED_REF_DENY", protectedRef.reasonCode());
      assertDoesNotThrow(
          () ->
              policy.require(
                  contextWithStaleGroups,
                  RepositoryAccessRequest.ref(
                      REPOSITORY,
                      RepositoryAccessOperation.UPDATE_REF,
                      "refs/heads/topic",
                      null,
                      null)));

      sessionFactory.inTransaction(
          session -> {
            SecurityRefRuleEntity rule = session.find(SecurityRefRuleEntity.class, "protect-main");
            rule.setEnabled(false);
          });
      assertDoesNotThrow(
          () ->
              policy.require(
                  contextWithStaleGroups,
                  RepositoryAccessRequest.ref(
                      REPOSITORY,
                      RepositoryAccessOperation.UPDATE_REF,
                      "refs/heads/main",
                      null,
                      null)));

      sessionFactory.inTransaction(
          session ->
              session
                  .createMutationQuery(
                      "DELETE FROM SecurityGroupMembership m WHERE m.principalId = :principalId")
                  .setParameter("principalId", "alice")
                  .executeUpdate());
      RepositoryAccessDeniedException membershipRevoked =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () ->
                  policy.require(
                      contextWithStaleGroups,
                      RepositoryAccessRequest.repository(
                          REPOSITORY, RepositoryAccessOperation.READ)));
      assertEquals("NO_MATCHING_GRANT", membershipRevoked.reasonCode());

      sessionFactory.inTransaction(
          session -> {
            SecurityPrincipalEntity principal =
                session.find(SecurityPrincipalEntity.class, "alice");
            principal.setStatus(SecurityPrincipalStatus.DISABLED);
          });
      RepositoryAccessDeniedException disabled =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () ->
                  policy.require(
                      contextWithStaleGroups,
                      RepositoryAccessRequest.repository(
                          REPOSITORY, RepositoryAccessOperation.READ)));
      assertEquals("PRINCIPAL_NOT_ACTIVE", disabled.reasonCode());
    }
  }

  private static RepositoryAccessRequest request(RepositoryAccessOperation operation) {
    return operation.refScoped()
        ? RepositoryAccessRequest.ref(
            REPOSITORY, operation, "refs/heads/main", null, null)
        : RepositoryAccessRequest.repository(REPOSITORY, operation);
  }

  private static void persistPolicy(SessionFactory sessionFactory) {
    Instant now = Instant.parse("2026-08-12T00:00:00Z");
    sessionFactory.inTransaction(
        session -> {
          SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
          principal.setPrincipalId("alice");
          principal.setPrincipalType(SecurityPrincipalType.EXTERNAL);
          principal.setLoginName("alice@example.test");
          principal.setDisplayName("Alice");
          principal.setExternalIssuer("https://issuer.example.test");
          principal.setExternalSubject("subject-alice");
          principal.setStatus(SecurityPrincipalStatus.ACTIVE);
          principal.setCreatedAt(now);
          principal.setUpdatedAt(now);
          principal.setSecurityVersion(1);

          SecurityGroupEntity group = new SecurityGroupEntity();
          group.setGroupId("contributors");
          group.setGroupName("Contributors");
          group.setDescription("Repository contributors");
          group.setStatus(SecurityGroupStatus.ACTIVE);
          group.setCreatedAt(now);
          group.setUpdatedAt(now);
          group.setSecurityVersion(2);

          SecurityGroupMembershipEntity membership = new SecurityGroupMembershipEntity();
          membership.setMembershipId("membership-alice-contributors");
          membership.setGroupId("contributors");
          membership.setPrincipalId("alice");
          membership.setCreatedAt(now);
          membership.setCreatedBy("system");
          membership.setSecurityVersion(3);

          session.persist(principal);
          session.persist(group);
          session.persist(membership);
          session.persist(
              grant(
                  "allow-read",
                  GitRepositoryPermission.READ,
                  SecurityEffect.ALLOW,
                  now,
                  4));
          session.persist(
              grant(
                  "allow-update",
                  GitRepositoryPermission.UPDATE_REF,
                  SecurityEffect.ALLOW,
                  now,
                  5));

          SecurityRefRuleEntity rule = new SecurityRefRuleEntity();
          rule.setRuleId("protect-main");
          rule.setRepositoryName(REPOSITORY.value());
          rule.setRefPattern("refs/heads/main");
          rule.setPermission(GitRepositoryPermission.UPDATE_REF);
          rule.setEffect(SecurityEffect.DENY);
          rule.setPriority(100);
          rule.setEnabled(true);
          rule.setCreatedAt(now);
          rule.setCreatedBy("system");
          rule.setSecurityVersion(6);
          session.persist(rule);
        });
  }

  private static SecurityRepositoryGrantEntity grant(
      String id,
      GitRepositoryPermission permission,
      SecurityEffect effect,
      Instant createdAt,
      long version) {
    SecurityRepositoryGrantEntity grant = new SecurityRepositoryGrantEntity();
    grant.setGrantId(id);
    grant.setRepositoryName(REPOSITORY.value());
    grant.setSubjectType(SecuritySubjectType.GROUP);
    grant.setSubjectId("contributors");
    grant.setPermission(permission);
    grant.setEffect(effect);
    grant.setCreatedAt(createdAt);
    grant.setCreatedBy("system");
    grant.setSecurityVersion(version);
    return grant;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:security-access-policy-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties, SecurityEntities.annotatedClasses());
  }
}
