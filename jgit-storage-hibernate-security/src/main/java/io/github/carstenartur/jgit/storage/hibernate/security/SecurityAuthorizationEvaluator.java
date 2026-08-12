/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-neutral, fail-closed evaluator for repository grants and protected-ref rules.
 *
 * <p>Repository grants are always required. Explicit deny grants win over allow grants. The
 * highest-precedence matching ref rule then refines a ref-scoped request. Rule precedence is
 * priority descending, specificity descending and stable ID ascending.
 */
public final class SecurityAuthorizationEvaluator {

  private static final Comparator<RepositoryRefRule> RULE_PRECEDENCE =
      Comparator.comparingInt(RepositoryRefRule::priority)
          .reversed()
          .thenComparing(Comparator.comparingInt(RepositoryRefRule::specificity).reversed())
          .thenComparing(RepositoryRefRule::id);

  private final List<RepositoryGrant> grants;
  private final List<RepositoryRefRule> refRules;
  private final long policyVersion;

  /** Creates an immutable evaluator from one policy snapshot. */
  public SecurityAuthorizationEvaluator(
      List<RepositoryGrant> grants, List<RepositoryRefRule> refRules) {
    this.grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
    this.refRules = List.copyOf(Objects.requireNonNull(refRules, "refRules"));
    validateUniqueIds(this.grants, this.refRules);
    this.policyVersion = latestVersion(this.grants, this.refRules);
  }

  /** Evaluates one request for one explicitly authenticated context. */
  public AuthorizationDecision authorize(
      GitAccessContext context, RepositoryAuthorizationRequest request) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");

    List<RepositoryGrant> matchingGrants =
        grants.stream()
            .filter(grant -> grant.repositoryName().equals(request.repositoryName()))
            .filter(grant -> grant.subject().matches(context))
            .toList();
    Set<GitRepositoryPermission> effectivePermissions = effectivePermissions(matchingGrants);

    RepositoryGrant denyingGrant =
        matchingGrants.stream()
            .filter(grant -> grant.effect() == SecurityEffect.DENY)
            .filter(grant -> grant.permission().includes(request.permission()))
            .min(Comparator.comparing(RepositoryGrant::id))
            .orElse(null);
    if (denyingGrant != null) {
      return decision(
          false,
          AuthorizationReason.EXPLICIT_GRANT_DENY,
          denyingGrant.id(),
          effectivePermissions);
    }

    RepositoryGrant allowingGrant =
        matchingGrants.stream()
            .filter(grant -> grant.effect() == SecurityEffect.ALLOW)
            .filter(grant -> grant.permission().includes(request.permission()))
            .min(Comparator.comparing(RepositoryGrant::id))
            .orElse(null);
    if (allowingGrant == null) {
      return decision(
          false, AuthorizationReason.NO_MATCHING_GRANT, null, effectivePermissions);
    }

    if (!request.refScoped()) {
      return decision(
          true, AuthorizationReason.GRANT_ALLOWED, allowingGrant.id(), effectivePermissions);
    }

    RepositoryRefRule matchingRule =
        refRules.stream()
            .filter(rule -> rule.matches(context, request))
            .sorted(RULE_PRECEDENCE)
            .findFirst()
            .orElse(null);
    if (matchingRule == null) {
      return decision(
          true, AuthorizationReason.GRANT_ALLOWED, allowingGrant.id(), effectivePermissions);
    }
    if (matchingRule.effect() == SecurityEffect.DENY) {
      return decision(
          false,
          AuthorizationReason.PROTECTED_REF_DENY,
          matchingRule.id(),
          effectivePermissions);
    }
    return decision(
        true, AuthorizationReason.REF_RULE_ALLOWED, matchingRule.id(), effectivePermissions);
  }

  /** Returns the monotonically increasing version represented by this immutable snapshot. */
  public long policyVersion() {
    return policyVersion;
  }

  private AuthorizationDecision decision(
      boolean allowed,
      AuthorizationReason reason,
      String evidenceId,
      Set<GitRepositoryPermission> effectivePermissions) {
    return new AuthorizationDecision(
        allowed, reason, evidenceId, policyVersion, effectivePermissions);
  }

  private static Set<GitRepositoryPermission> effectivePermissions(
      List<RepositoryGrant> matchingGrants) {
    EnumSet<GitRepositoryPermission> result = EnumSet.noneOf(GitRepositoryPermission.class);
    for (GitRepositoryPermission permission : GitRepositoryPermission.values()) {
      boolean denied =
          matchingGrants.stream()
              .anyMatch(
                  grant ->
                      grant.effect() == SecurityEffect.DENY
                          && grant.permission().includes(permission));
      boolean allowed =
          matchingGrants.stream()
              .anyMatch(
                  grant ->
                      grant.effect() == SecurityEffect.ALLOW
                          && grant.permission().includes(permission));
      if (allowed && !denied) {
        result.add(permission);
      }
    }
    return Set.copyOf(result);
  }

  private static void validateUniqueIds(
      List<RepositoryGrant> grants, List<RepositoryRefRule> refRules) {
    Set<String> policyIds = new HashSet<>();
    for (RepositoryGrant grant : grants) {
      if (!policyIds.add(grant.id())) {
        throw new SecurityPolicyConfigurationException(
            "duplicate repository grant id: " + grant.id());
      }
    }

    Set<String> ruleIds = new HashSet<>();
    for (RepositoryRefRule rule : refRules) {
      if (!ruleIds.add(rule.id())) {
        throw new SecurityPolicyConfigurationException(
            "duplicate repository ref rule id: " + rule.id());
      }
      if (!policyIds.add(rule.id())) {
        throw new SecurityPolicyConfigurationException(
            "repository grant and ref rule share policy id: " + rule.id());
      }
    }
  }

  private static long latestVersion(
      List<RepositoryGrant> grants, List<RepositoryRefRule> refRules) {
    List<Long> versions = new ArrayList<>(grants.size() + refRules.size());
    grants.forEach(grant -> versions.add(grant.securityVersion()));
    refRules.forEach(rule -> versions.add(rule.securityVersion()));
    return versions.stream().mapToLong(Long::longValue).max().orElse(0);
  }
}
