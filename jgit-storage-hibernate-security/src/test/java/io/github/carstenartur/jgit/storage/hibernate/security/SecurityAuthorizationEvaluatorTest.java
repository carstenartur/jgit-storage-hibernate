/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityAuthorizationEvaluatorTest {

  private static final RepositoryName REPOSITORY = new RepositoryName("workflows");
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of("contributors"), "oidc", "session", "correlation", Map.of());

  @Test
  void principalAndGroupAllowsAreAdditiveButExplicitDenyWins() {
    SecurityAuthorizationEvaluator evaluator =
        evaluator(
            grant(
                "read",
                SecuritySubject.principal("alice"),
                GitRepositoryPermission.READ,
                SecurityEffect.ALLOW,
                1),
            grant(
                "write",
                SecuritySubject.group("contributors"),
                GitRepositoryPermission.UPDATE_REF,
                SecurityEffect.ALLOW,
                2),
            grant(
                "deny-write",
                SecuritySubject.principal("alice"),
                GitRepositoryPermission.UPDATE_REF,
                SecurityEffect.DENY,
                3));

    AuthorizationDecision read =
        evaluator.authorize(
            ALICE,
            RepositoryAuthorizationRequest.repository(
                REPOSITORY, GitRepositoryPermission.READ));
    AuthorizationDecision write =
        evaluator.authorize(
            ALICE,
            RepositoryAuthorizationRequest.repository(
                REPOSITORY, GitRepositoryPermission.UPDATE_REF));

    assertTrue(read.allowed());
    assertEquals(AuthorizationReason.GRANT_ALLOWED, read.reason());
    assertFalse(write.allowed());
    assertEquals(AuthorizationReason.EXPLICIT_GRANT_DENY, write.reason());
    assertEquals("deny-write", write.evidenceId());
    assertEquals(3, write.policyVersion());
  }

  @Test
  void administerCoversEveryPermissionUnlessARequestedPermissionIsDenied() {
    SecurityAuthorizationEvaluator evaluator =
        evaluator(
            grant(
                "admin",
                SecuritySubject.principal("alice"),
                GitRepositoryPermission.ADMINISTER,
                SecurityEffect.ALLOW,
                4),
            grant(
                "no-force",
                SecuritySubject.principal("alice"),
                GitRepositoryPermission.FORCE_UPDATE,
                SecurityEffect.DENY,
                5));

    assertTrue(
        evaluator
            .authorize(
                ALICE,
                RepositoryAuthorizationRequest.repository(
                    REPOSITORY, GitRepositoryPermission.DELETE_REF))
            .allowed());
    assertFalse(
        evaluator
            .authorize(
                ALICE,
                RepositoryAuthorizationRequest.repository(
                    REPOSITORY, GitRepositoryPermission.FORCE_UPDATE))
            .allowed());
  }

  @Test
  void protectedRefRuleUsesPrioritySpecificityAndStableId() {
    SecurityAuthorizationEvaluator evaluator =
        new SecurityAuthorizationEvaluator(
            List.of(
                grant(
                    "write",
                    SecuritySubject.group("contributors"),
                    GitRepositoryPermission.UPDATE_REF,
                    SecurityEffect.ALLOW,
                    1)),
            List.of(
                rule("z-generic", "refs/heads/**", SecurityEffect.DENY, 10, null, 2),
                rule(
                    "b-integration",
                    "refs/heads/integration/*",
                    SecurityEffect.ALLOW,
                    10,
                    null,
                    3),
                rule(
                    "a-release",
                    "refs/heads/integration/release",
                    SecurityEffect.DENY,
                    11,
                    null,
                    4)));

    AuthorizationDecision feature =
        evaluator.authorize(
            ALICE,
            RepositoryAuthorizationRequest.ref(
                REPOSITORY,
                GitRepositoryPermission.UPDATE_REF,
                "refs/heads/integration/feature"));
    AuthorizationDecision release =
        evaluator.authorize(
            ALICE,
            RepositoryAuthorizationRequest.ref(
                REPOSITORY,
                GitRepositoryPermission.UPDATE_REF,
                "refs/heads/integration/release"));

    assertTrue(feature.allowed());
    assertEquals("b-integration", feature.evidenceId());
    assertFalse(release.allowed());
    assertEquals(AuthorizationReason.PROTECTED_REF_DENY, release.reason());
    assertEquals("a-release", release.evidenceId());
  }

  @Test
  void subjectConstrainedRuleAndDoubleStarHaveStableSemantics() {
    SecurityAuthorizationEvaluator evaluator =
        new SecurityAuthorizationEvaluator(
            List.of(
                grant(
                    "write",
                    SecuritySubject.group("contributors"),
                    GitRepositoryPermission.UPDATE_REF,
                    SecurityEffect.ALLOW,
                    1)),
            List.of(
                rule(
                    "alice-only",
                    "refs/heads/users/**",
                    SecurityEffect.ALLOW,
                    20,
                    SecuritySubject.principal("alice"),
                    2),
                rule(
                    "protected",
                    "refs/heads/users/*",
                    SecurityEffect.DENY,
                    10,
                    null,
                    3)));

    assertTrue(
        evaluator
            .authorize(
                ALICE,
                RepositoryAuthorizationRequest.ref(
                    REPOSITORY,
                    GitRepositoryPermission.UPDATE_REF,
                    "refs/heads/users/alice/topic"))
            .allowed());
    assertTrue(GitRefPattern.matches("refs/heads/**", "refs/heads/a/b"));
    assertFalse(GitRefPattern.matches("refs/heads/*", "refs/heads/a/b"));
  }

  @Test
  void evidenceSelectionIsStableAcrossInputOrder() {
    RepositoryGrant zAllow =
        grant(
            "z-allow",
            SecuritySubject.principal("alice"),
            GitRepositoryPermission.READ,
            SecurityEffect.ALLOW,
            1);
    RepositoryGrant aAllow =
        grant(
            "a-allow",
            SecuritySubject.group("contributors"),
            GitRepositoryPermission.READ,
            SecurityEffect.ALLOW,
            2);

    AuthorizationDecision decision =
        new SecurityAuthorizationEvaluator(List.of(zAllow, aAllow), List.of())
            .authorize(
                ALICE,
                RepositoryAuthorizationRequest.repository(
                    REPOSITORY, GitRepositoryPermission.READ));

    assertTrue(decision.allowed());
    assertEquals("a-allow", decision.evidenceId());
  }

  @Test
  void noMatchingGrantFailsClosedAndDuplicatePolicyIdsAreRejected() {
    SecurityAuthorizationEvaluator evaluator =
        new SecurityAuthorizationEvaluator(List.of(), List.of());

    AuthorizationDecision decision =
        evaluator.authorize(
            ALICE,
            RepositoryAuthorizationRequest.repository(
                REPOSITORY, GitRepositoryPermission.DISCOVER));

    assertFalse(decision.allowed());
    assertEquals(AuthorizationReason.NO_MATCHING_GRANT, decision.reason());
    assertThrows(
        SecurityPolicyConfigurationException.class,
        () ->
            new SecurityAuthorizationEvaluator(
                List.of(
                    grant(
                        "same",
                        SecuritySubject.principal("alice"),
                        GitRepositoryPermission.READ,
                        SecurityEffect.ALLOW,
                        1),
                    grant(
                        "same",
                        SecuritySubject.principal("alice"),
                        GitRepositoryPermission.READ,
                        SecurityEffect.DENY,
                        2)),
                List.of()));
    assertThrows(
        SecurityPolicyConfigurationException.class,
        () ->
            new SecurityAuthorizationEvaluator(
                List.of(),
                List.of(
                    rule("same", "refs/heads/**", SecurityEffect.ALLOW, 1, null, 1),
                    rule("same", "refs/tags/**", SecurityEffect.DENY, 2, null, 2))));
  }

  @Test
  void evidenceIdsMustBeUniqueAcrossGrantAndRefRuleTypes() {
    SecurityPolicyConfigurationException exception =
        assertThrows(
            SecurityPolicyConfigurationException.class,
            () ->
                new SecurityAuthorizationEvaluator(
                    List.of(
                        grant(
                            "shared",
                            SecuritySubject.principal("alice"),
                            GitRepositoryPermission.UPDATE_REF,
                            SecurityEffect.ALLOW,
                            1)),
                    List.of(
                        rule(
                            "shared",
                            "refs/heads/**",
                            SecurityEffect.DENY,
                            10,
                            null,
                            2))));

    assertEquals(
        "repository grant and ref rule share policy id: shared", exception.getMessage());
  }

  @Test
  void persistedIdentifierLimitsAreEnforcedBeforePersistence() {
    String maximum = "x".repeat(128);
    assertEquals(
        maximum,
        grant(
                maximum,
                SecuritySubject.principal("alice"),
                GitRepositoryPermission.READ,
                SecurityEffect.ALLOW,
                1)
            .id());
    assertEquals(
        maximum,
        rule(maximum, "refs/heads/**", SecurityEffect.ALLOW, 1, null, 1).id());
    assertEquals(maximum, SecuritySubject.principal(maximum).id());

    String tooLong = "x".repeat(129);
    IllegalArgumentException grantException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                grant(
                    tooLong,
                    SecuritySubject.principal("alice"),
                    GitRepositoryPermission.READ,
                    SecurityEffect.ALLOW,
                    1));
    IllegalArgumentException ruleException =
        assertThrows(
            IllegalArgumentException.class,
            () -> rule(tooLong, "refs/heads/**", SecurityEffect.ALLOW, 1, null, 1));
    IllegalArgumentException subjectException =
        assertThrows(
            IllegalArgumentException.class, () -> SecuritySubject.principal(tooLong));

    assertEquals("grant id must contain 1 to 128 characters", grantException.getMessage());
    assertEquals("rule id must contain 1 to 128 characters", ruleException.getMessage());
    assertEquals(
        "subject id must contain at most 128 characters", subjectException.getMessage());
  }

  private static SecurityAuthorizationEvaluator evaluator(RepositoryGrant... grants) {
    return new SecurityAuthorizationEvaluator(List.of(grants), List.of());
  }

  private static RepositoryGrant grant(
      String id,
      SecuritySubject subject,
      GitRepositoryPermission permission,
      SecurityEffect effect,
      long version) {
    return new RepositoryGrant(id, subject, REPOSITORY, permission, effect, version);
  }

  private static RepositoryRefRule rule(
      String id,
      String pattern,
      SecurityEffect effect,
      int priority,
      SecuritySubject subject,
      long version) {
    return new RepositoryRefRule(
        id,
        REPOSITORY,
        pattern,
        GitRepositoryPermission.UPDATE_REF,
        effect,
        priority,
        subject,
        version);
  }
}
