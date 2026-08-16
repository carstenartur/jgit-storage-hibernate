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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityManagedPolicyEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class HibernateRepositoryAuthorizationPolicySynchronizerTest {

  private static final RepositoryName REPOSITORY = new RepositoryName("taxonomy-main");
  private static final ManagedPolicySource SOURCE =
      new ManagedPolicySource("taxonomy", "production");
  private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
  private static final GitAccessContext ADMIN =
      new GitAccessContext(
          "admin", Set.of(), "oidc_session", "session-admin", "correlation-admin", Map.of());
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of(), "oidc_jwt", "session-alice", "correlation-alice", Map.of());
  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

  @Test
  void appliesNoOpsUpdatesAndImmediatelyChangesEffectiveAuthorization() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      HibernateRepositoryAuthorizationPolicySynchronizer synchronizer =
          synchronizer(sessionFactory);

      RepositoryAuthorizationPolicySnapshot versionOne =
          snapshot(
              1,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(
                  grant("alice-read", "alice", GitRepositoryPermission.READ),
                  grant("alice-update", "alice", GitRepositoryPermission.UPDATE_REF)),
              Set.of(
                  DesiredRepositoryRefRule.global(
                      "protect-main",
                      "refs/heads/main",
                      GitRepositoryPermission.UPDATE_REF,
                      SecurityEffect.DENY,
                      100)));
      RepositoryAuthorizationPolicySyncResult applied =
          synchronizer.synchronize(versionOne, 0, ADMIN, "policy-operation-1");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.APPLIED, applied.status());
      assertEquals("POLICY_APPLIED", applied.reasonCode());
      assertEquals(0, applied.previousPolicyVersion());
      assertEquals(1, applied.activePolicyVersion());
      assertEquals(1, applied.policyGeneration());
      assertEquals(3, applied.createdEntries());
      assertEquals(0, applied.updatedEntries());
      assertEquals(0, applied.deletedEntries());
      assertTrue(applied.contentDigest().matches("[0-9a-f]{64}"));

      HibernateSecurityRepositoryAccessPolicy accessPolicy =
          new HibernateSecurityRepositoryAccessPolicy(sessionFactory);
      assertDoesNotThrow(
          () ->
              accessPolicy.require(
                  ALICE,
                  RepositoryAccessRequest.repository(REPOSITORY, RepositoryAccessOperation.READ)));
      RepositoryAccessDeniedException mainDenied =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () ->
                  accessPolicy.require(
                      ALICE,
                      RepositoryAccessRequest.ref(
                          REPOSITORY,
                          RepositoryAccessOperation.UPDATE_REF,
                          "refs/heads/main",
                          null,
                          null)));
      assertEquals("PROTECTED_REF_DENY", mainDenied.reasonCode());
      assertDoesNotThrow(
          () ->
              accessPolicy.require(
                  ALICE,
                  RepositoryAccessRequest.ref(
                      REPOSITORY,
                      RepositoryAccessOperation.UPDATE_REF,
                      "refs/heads/topic",
                      null,
                      null)));

      RepositoryAuthorizationPolicySyncResult noOp =
          synchronizer.synchronize(versionOne, 0, ADMIN, "policy-operation-retry");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.NO_OP, noOp.status());
      assertEquals("POLICY_ALREADY_ACTIVE", noOp.reasonCode());
      assertEquals(1, noOp.policyGeneration());
      assertEquals(0, noOp.createdEntries());
      assertEquals(0, noOp.updatedEntries());
      assertEquals(0, noOp.deletedEntries());

      RepositoryAuthorizationPolicySnapshot versionTwo =
          snapshot(
              2,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
              Set.of());
      RepositoryAuthorizationPolicySyncResult updated =
          synchronizer.synchronize(versionTwo, 1, ADMIN, "policy-operation-2");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.APPLIED, updated.status());
      assertEquals(1, updated.previousPolicyVersion());
      assertEquals(2, updated.activePolicyVersion());
      assertEquals(2, updated.policyGeneration());
      assertEquals(0, updated.createdEntries());
      assertEquals(1, updated.updatedEntries());
      assertEquals(2, updated.deletedEntries());

      RepositoryAccessDeniedException updateDenied =
          assertThrows(
              RepositoryAccessDeniedException.class,
              () ->
                  accessPolicy.require(
                      ALICE,
                      RepositoryAccessRequest.ref(
                          REPOSITORY,
                          RepositoryAccessOperation.UPDATE_REF,
                          "refs/heads/topic",
                          null,
                          null)));
      assertEquals("NO_MATCHING_GRANT", updateDenied.reasonCode());

      sessionFactory.inTransaction(
          session -> {
            SecurityManagedPolicyEntity head =
                session
                    .createSelectionQuery(
                        "FROM SecurityManagedPolicy p WHERE p.repositoryName = :repositoryName",
                        SecurityManagedPolicyEntity.class)
                    .setParameter("repositoryName", REPOSITORY.value())
                    .getSingleResult();
            assertEquals(2, head.getPolicyVersion());
            assertEquals(2, head.getPolicyGeneration());
            assertEquals("admin", head.getCreatedByPrincipalId());
            assertEquals("admin", head.getUpdatedByPrincipalId());
            assertEquals("policy-operation-2", head.getLastOperationId());
            assertEquals("correlation-admin", head.getLastCorrelationId());
            assertEquals(RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY, head.getOwnershipMode());

            List<SecurityRepositoryGrantEntity> grants =
                session
                    .createSelectionQuery(
                        "FROM SecurityRepositoryGrant g WHERE g.repositoryName = :repositoryName",
                        SecurityRepositoryGrantEntity.class)
                    .setParameter("repositoryName", REPOSITORY.value())
                    .getResultList();
            assertEquals(1, grants.size());
            assertEquals("alice-read", grants.get(0).getManagedEntryKey());
            assertEquals(2L, grants.get(0).getManagedPolicyVersion());
            assertEquals(2, grants.get(0).getSecurityVersion());
            assertEquals(0L, session.createSelectionQuery("SELECT count(r) FROM SecurityRefRule r", Long.class).getSingleResult());
          });
    }
  }

  @Test
  void returnsStableStaleDigestAndExpectedVersionConflicts() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      HibernateRepositoryAuthorizationPolicySynchronizer synchronizer =
          synchronizer(sessionFactory);
      RepositoryAuthorizationPolicySnapshot versionTwo =
          snapshot(
              2,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
              Set.of());
      assertEquals(
          RepositoryAuthorizationPolicySyncStatus.APPLIED,
          synchronizer.synchronize(versionTwo, 0, ADMIN, "op-v2").status());

      RepositoryAuthorizationPolicySyncResult stale =
          synchronizer.synchronize(
              snapshot(
                  1,
                  RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                  Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
                  Set.of()),
              2,
              ADMIN,
              "op-stale");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.STALE, stale.status());
      assertEquals("STALE_POLICY_VERSION", stale.reasonCode());
      assertEquals(2, stale.activePolicyVersion());

      RepositoryAuthorizationPolicySyncResult digestConflict =
          synchronizer.synchronize(
              snapshot(
                  2,
                  RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                  Set.of(grant("alice-update", "alice", GitRepositoryPermission.UPDATE_REF)),
                  Set.of()),
              2,
              ADMIN,
              "op-digest-conflict");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.CONFLICT, digestConflict.status());
      assertEquals("POLICY_VERSION_DIGEST_CONFLICT", digestConflict.reasonCode());

      RepositoryAuthorizationPolicySyncResult expectedConflict =
          synchronizer.synchronize(
              snapshot(
                  3,
                  RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                  Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
                  Set.of()),
              1,
              ADMIN,
              "op-cas-conflict");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.CONFLICT, expectedConflict.status());
      assertEquals("EXPECTED_POLICY_VERSION_MISMATCH", expectedConflict.reasonCode());
    }
  }

  @Test
  void exclusiveModeRejectsOutsideRowsWhileNamespaceModePreservesThem() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "bob", SecurityPrincipalStatus.ACTIVE);
      persistManualGrant(
          sessionFactory,
          "manual-bob-discover",
          "bob",
          GitRepositoryPermission.DISCOVER,
          SecurityEffect.ALLOW);
      HibernateRepositoryAuthorizationPolicySynchronizer synchronizer =
          synchronizer(sessionFactory);

      RepositoryAuthorizationPolicySyncResult exclusive =
          synchronizer.synchronize(
              snapshot(
                  1,
                  RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                  Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
                  Set.of()),
              0,
              ADMIN,
              "exclusive-conflict");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.CONFLICT, exclusive.status());
      assertEquals("OUTSIDE_NAMESPACE_POLICY_PRESENT", exclusive.reasonCode());
      assertEquals(1, exclusive.outsideNamespaceGrantCount());

      RepositoryAuthorizationPolicySyncResult namespace =
          synchronizer.synchronize(
              snapshot(
                  1,
                  RepositoryPolicyOwnershipMode.NAMESPACE_ONLY,
                  Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
                  Set.of()),
              0,
              ADMIN,
              "namespace-apply");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.APPLIED, namespace.status());
      assertEquals(1, namespace.outsideNamespaceGrantCount());
      sessionFactory.inTransaction(
          session ->
              assertEquals(
                  2L,
                  session
                      .createSelectionQuery(
                          "SELECT count(g) FROM SecurityRepositoryGrant g", Long.class)
                      .getSingleResult()));
    }
  }

  @Test
  void namespaceModeRejectsAConflictingManualGrantSemantic() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      persistManualGrant(
          sessionFactory,
          "manual-alice-read",
          "alice",
          GitRepositoryPermission.READ,
          SecurityEffect.ALLOW);

      RepositoryAuthorizationPolicySyncResult conflict =
          synchronizer(sessionFactory)
              .synchronize(
                  snapshot(
                      1,
                      RepositoryPolicyOwnershipMode.NAMESPACE_ONLY,
                      Set.of(grant("managed-alice-read", "alice", GitRepositoryPermission.READ)),
                      Set.of()),
                  0,
                  ADMIN,
                  "semantic-conflict");
      assertEquals(RepositoryAuthorizationPolicySyncStatus.CONFLICT, conflict.status());
      assertEquals("GRANT_SEMANTIC_CONFLICT", conflict.reasonCode());
    }
  }

  @Test
  void validatesActiveActorsPrincipalsAndGroupsBeforeMutation() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "disabled-admin", SecurityPrincipalStatus.DISABLED);
      HibernateRepositoryAuthorizationPolicySynchronizer synchronizer =
          synchronizer(sessionFactory);

      SecurityPolicyConfigurationException missingPrincipal =
          assertThrows(
              SecurityPolicyConfigurationException.class,
              () ->
                  synchronizer.synchronize(
                      snapshot(
                          1,
                          RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                          Set.of(grant("missing-read", "missing", GitRepositoryPermission.READ)),
                          Set.of()),
                      0,
                      ADMIN,
                      "missing-principal"));
      assertTrue(missingPrincipal.getMessage().contains("missing or inactive principal"));

      persistGroup(sessionFactory, "disabled-group", SecurityGroupStatus.DISABLED);
      SecurityPolicyConfigurationException disabledGroup =
          assertThrows(
              SecurityPolicyConfigurationException.class,
              () ->
                  synchronizer.synchronize(
                      snapshot(
                          1,
                          RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                          Set.of(
                              new DesiredRepositoryGrant(
                                  "group-read",
                                  SecuritySubject.group("disabled-group"),
                                  GitRepositoryPermission.READ,
                                  SecurityEffect.ALLOW)),
                          Set.of()),
                      0,
                      ADMIN,
                      "disabled-group"));
      assertTrue(disabledGroup.getMessage().contains("missing or inactive group"));

      GitAccessContext disabledActor =
          new GitAccessContext(
              "disabled-admin", Set.of(), "oidc", "session", "correlation", Map.of());
      SecurityAuthenticationException inactiveActor =
          assertThrows(
              SecurityAuthenticationException.class,
              () ->
                  synchronizer.synchronize(
                      snapshot(
                          1,
                          RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                          Set.of(),
                          Set.of()),
                      0,
                      disabledActor,
                      "disabled-actor"));
      assertEquals(SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE, inactiveActor.reason());
      sessionFactory.inTransaction(
          session ->
              assertEquals(
                  0L,
                  session
                      .createSelectionQuery(
                          "SELECT count(p) FROM SecurityManagedPolicy p", Long.class)
                      .getSingleResult()));
    }
  }

  @Test
  void appliesActiveGroupPolicyAndUsesDatabaseMembershipRatherThanCallerGroups() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      persistGroup(sessionFactory, "contributors", SecurityGroupStatus.ACTIVE);
      persistMembership(sessionFactory, "alice", "contributors");
      RepositoryAuthorizationPolicySnapshot desired =
          snapshot(
              1,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(
                  new DesiredRepositoryGrant(
                      "contributors-read",
                      SecuritySubject.group("contributors"),
                      GitRepositoryPermission.READ,
                      SecurityEffect.ALLOW)),
              Set.of());
      assertEquals(
          RepositoryAuthorizationPolicySyncStatus.APPLIED,
          synchronizer(sessionFactory).synchronize(desired, 0, ADMIN, "group-policy").status());

      GitAccessContext callerWithoutGroups =
          new GitAccessContext(
              "alice", Set.of(), "oidc", "session", "correlation", Map.of());
      assertDoesNotThrow(
          () ->
              new HibernateSecurityRepositoryAccessPolicy(sessionFactory)
                  .require(
                      callerWithoutGroups,
                      RepositoryAccessRequest.repository(
                          REPOSITORY, RepositoryAccessOperation.READ)));
    }
  }

  @Test
  void concurrentIdenticalFirstApplyProducesOneApplyAndOneNoOp() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      RepositoryAuthorizationPolicySnapshot desired =
          snapshot(
              1,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(grant("alice-read", "alice", GitRepositoryPermission.READ)),
              Set.of());
      CyclicBarrier start = new CyclicBarrier(2);
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        List<Future<RepositoryAuthorizationPolicySyncResult>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
          int request = index;
          futures.add(
              executor.submit(
                  () -> {
                    start.await();
                    return synchronizer(sessionFactory)
                        .synchronize(desired, 0, ADMIN, "concurrent-" + request);
                  }));
        }
        EnumSet<RepositoryAuthorizationPolicySyncStatus> statuses =
            EnumSet.noneOf(RepositoryAuthorizationPolicySyncStatus.class);
        for (Future<RepositoryAuthorizationPolicySyncResult> future : futures) {
          statuses.add(future.get().status());
        }
        assertEquals(
            EnumSet.of(
                RepositoryAuthorizationPolicySyncStatus.APPLIED,
                RepositoryAuthorizationPolicySyncStatus.NO_OP),
            statuses);
      }
      sessionFactory.inTransaction(
          session -> {
            assertEquals(
                1L,
                session
                    .createSelectionQuery(
                        "SELECT count(p) FROM SecurityManagedPolicy p", Long.class)
                    .getSingleResult());
            assertEquals(
                1L,
                session
                    .createSelectionQuery(
                        "SELECT count(g) FROM SecurityRepositoryGrant g", Long.class)
                    .getSingleResult());
          });
    }
  }

  @Test
  void rejectsMalformedSnapshotsOperationsAndResultEvidence() {
    assertThrows(IllegalArgumentException.class, () -> new ManagedPolicySource(" ", "instance"));
    assertThrows(
        IllegalArgumentException.class, () -> new ManagedPolicySource("source", " instance "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManagedPolicySource("source\n", "instance"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManagedPolicySource("x".repeat(129), "instance"));
    assertThrows(
        IllegalArgumentException.class,
        () -> grant(" bad ", "alice", GitRepositoryPermission.READ));
    assertThrows(
        IllegalArgumentException.class,
        () -> grant("bad\nkey", "alice", GitRepositoryPermission.READ));
    assertThrows(
        IllegalArgumentException.class,
        () -> grant("x".repeat(257), "alice", GitRepositoryPermission.READ));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesiredRepositoryRefRule(
                "rule",
                "main",
                GitRepositoryPermission.UPDATE_REF,
                SecurityEffect.DENY,
                1,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesiredRepositoryRefRule(
                "rule",
                "refs/heads/main",
                GitRepositoryPermission.UPDATE_REF,
                SecurityEffect.DENY,
                1_000_001,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryAuthorizationPolicySnapshot(
                REPOSITORY,
                SOURCE,
                0,
                RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                Set.of(),
                Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryAuthorizationPolicySnapshot(
                REPOSITORY,
                SOURCE,
                1,
                RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                Set.of(grant("same", "alice", GitRepositoryPermission.READ)),
                Set.of(
                    DesiredRepositoryRefRule.global(
                        "same",
                        "refs/heads/main",
                        GitRepositoryPermission.UPDATE_REF,
                        SecurityEffect.DENY,
                        1))));

    try (HibernateSessionFactoryProvider provider = provider()) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "admin", SecurityPrincipalStatus.ACTIVE);
      persistPrincipal(sessionFactory, "alice", SecurityPrincipalStatus.ACTIVE);
      HibernateRepositoryAuthorizationPolicySynchronizer synchronizer =
          synchronizer(sessionFactory);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              synchronizer.synchronize(
                  snapshot(
                      1,
                      RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                      Set.of(grant("one", "alice", GitRepositoryPermission.READ)),
                      Set.of()),
                  -1,
                  ADMIN,
                  "operation"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              synchronizer.synchronize(
                  snapshot(
                      1,
                      RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
                      Set.of(grant("one", "alice", GitRepositoryPermission.READ)),
                      Set.of()),
                  0,
                  ADMIN,
                  " blank "));

      RepositoryAuthorizationPolicySnapshot duplicateGrantSemantic =
          snapshot(
              1,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(
                  grant("one", "alice", GitRepositoryPermission.READ),
                  grant("two", "alice", GitRepositoryPermission.READ)),
              Set.of());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              synchronizer.synchronize(
                  duplicateGrantSemantic, 0, ADMIN, "duplicate-grant"));

      RepositoryAuthorizationPolicySnapshot ambiguousRules =
          snapshot(
              1,
              RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY,
              Set.of(),
              Set.of(
                  DesiredRepositoryRefRule.global(
                      "allow-main",
                      "refs/heads/main",
                      GitRepositoryPermission.UPDATE_REF,
                      SecurityEffect.ALLOW,
                      10),
                  DesiredRepositoryRefRule.global(
                      "deny-main",
                      "refs/heads/main",
                      GitRepositoryPermission.UPDATE_REF,
                      SecurityEffect.DENY,
                      10)));
      assertThrows(
          IllegalArgumentException.class,
          () -> synchronizer.synchronize(ambiguousRules, 0, ADMIN, "ambiguous-rules"));
    }

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryAuthorizationPolicySyncResult(
                RepositoryAuthorizationPolicySyncStatus.APPLIED,
                "",
                REPOSITORY,
                SOURCE,
                0,
                1,
                1,
                "0".repeat(64),
                0,
                0,
                0,
                0,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryAuthorizationPolicySyncResult(
                RepositoryAuthorizationPolicySyncStatus.APPLIED,
                "POLICY_APPLIED",
                REPOSITORY,
                SOURCE,
                0,
                1,
                1,
                "not-a-digest",
                0,
                0,
                0,
                0,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryAuthorizationPolicySyncResult(
                RepositoryAuthorizationPolicySyncStatus.APPLIED,
                "POLICY_APPLIED",
                REPOSITORY,
                SOURCE,
                0,
                1,
                1,
                "0".repeat(64),
                -1,
                0,
                0,
                0,
                0));
  }

  private static RepositoryAuthorizationPolicySnapshot snapshot(
      long version,
      RepositoryPolicyOwnershipMode ownershipMode,
      Set<DesiredRepositoryGrant> grants,
      Set<DesiredRepositoryRefRule> rules) {
    return new RepositoryAuthorizationPolicySnapshot(
        REPOSITORY, SOURCE, version, ownershipMode, grants, rules);
  }

  private static DesiredRepositoryGrant grant(
      String entryKey, String principalId, GitRepositoryPermission permission) {
    return new DesiredRepositoryGrant(
        entryKey, SecuritySubject.principal(principalId), permission, SecurityEffect.ALLOW);
  }

  private static HibernateRepositoryAuthorizationPolicySynchronizer synchronizer(
      SessionFactory sessionFactory) {
    return new HibernateRepositoryAuthorizationPolicySynchronizer(
        sessionFactory, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static void persistPrincipal(
      SessionFactory sessionFactory, String principalId, SecurityPrincipalStatus status) {
    sessionFactory.inTransaction(
        session -> {
          SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
          principal.setPrincipalId(principalId);
          principal.setPrincipalType(SecurityPrincipalType.EXTERNAL);
          principal.setLoginName(null);
          principal.setDisplayName(principalId);
          principal.setExternalIssuer("https://issuer.example.test");
          principal.setExternalSubject("subject-" + principalId);
          principal.setStatus(status);
          principal.setCreatedAt(NOW);
          principal.setUpdatedAt(NOW);
          principal.setSecurityVersion(1);
          session.persist(principal);
        });
  }

  private static void persistGroup(
      SessionFactory sessionFactory, String groupId, SecurityGroupStatus status) {
    sessionFactory.inTransaction(
        session -> {
          SecurityGroupEntity group = new SecurityGroupEntity();
          group.setGroupId(groupId);
          group.setGroupName(groupId);
          group.setDescription("Managed policy test group");
          group.setStatus(status);
          group.setCreatedAt(NOW);
          group.setUpdatedAt(NOW);
          group.setSecurityVersion(1);
          session.persist(group);
        });
  }

  private static void persistMembership(
      SessionFactory sessionFactory, String principalId, String groupId) {
    sessionFactory.inTransaction(
        session -> {
          SecurityGroupMembershipEntity membership = new SecurityGroupMembershipEntity();
          membership.setMembershipId("membership-" + principalId + "-" + groupId);
          membership.setPrincipalId(principalId);
          membership.setGroupId(groupId);
          membership.setCreatedAt(NOW);
          membership.setCreatedBy("admin");
          membership.setSecurityVersion(1);
          session.persist(membership);
        });
  }

  private static void persistManualGrant(
      SessionFactory sessionFactory,
      String grantId,
      String principalId,
      GitRepositoryPermission permission,
      SecurityEffect effect) {
    sessionFactory.inTransaction(
        session -> {
          SecurityRepositoryGrantEntity grant = new SecurityRepositoryGrantEntity();
          grant.setGrantId(grantId);
          grant.setRepositoryName(REPOSITORY.value());
          grant.setSubjectType(SecuritySubjectType.PRINCIPAL);
          grant.setSubjectId(principalId);
          grant.setPermission(permission);
          grant.setEffect(effect);
          grant.setCreatedAt(NOW);
          grant.setCreatedBy("admin");
          grant.setSecurityVersion(1);
          session.persist(grant);
        });
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:managed-policy-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties, SecurityEntities.annotatedClasses());
  }
}
