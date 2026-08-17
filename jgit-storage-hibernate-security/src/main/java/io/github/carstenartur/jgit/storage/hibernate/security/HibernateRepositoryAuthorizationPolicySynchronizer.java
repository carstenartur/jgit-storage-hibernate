/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityManagedPolicyEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityVersionEntity;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Hibernate-backed atomic desired-state synchronizer for application-managed repository policy.
 *
 * <p>A repository-scoped database generation row serializes policy activation across sources and
 * application instances. Existing manual or other-source rows are never deleted. Taxonomy-style
 * consumers can select {@link RepositoryPolicyOwnershipMode#EXCLUSIVE_REPOSITORY} to fail closed
 * while any such row exists.
 */
public final class HibernateRepositoryAuthorizationPolicySynchronizer
    implements RepositoryAuthorizationPolicySynchronizer {

  private static final int MAX_OPERATION_ID_LENGTH = 256;
  private static final String VERSION_SCOPE_PREFIX = "repository:";

  private final SessionFactory sessionFactory;
  private final Clock clock;

  /** Create a synchronizer using UTC timestamps. */
  public HibernateRepositoryAuthorizationPolicySynchronizer(SessionFactory sessionFactory) {
    this(sessionFactory, Clock.systemUTC());
  }

  HibernateRepositoryAuthorizationPolicySynchronizer(
      SessionFactory sessionFactory, Clock clock) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public RepositoryAuthorizationPolicySyncResult synchronize(
      RepositoryAuthorizationPolicySnapshot desired,
      long expectedCurrentPolicyVersion,
      GitAccessContext actor,
      String operationId) {
    RepositoryAuthorizationPolicySnapshot snapshot =
        Objects.requireNonNull(desired, "desired");
    GitAccessContext actorContext = Objects.requireNonNull(actor, "actor");
    if (expectedCurrentPolicyVersion < 0) {
      throw new IllegalArgumentException("expectedCurrentPolicyVersion must not be negative");
    }
    String operation = required("operationId", operationId, MAX_OPERATION_ID_LENGTH);
    validateDesiredSemantics(snapshot);
    String digest = contentDigest(snapshot);
    String scopeKey = versionScope(snapshot.repositoryName());
    ensureVersionScope(scopeKey);
    return sessionFactory.fromTransaction(
        session ->
            synchronize(
                session,
                snapshot,
                expectedCurrentPolicyVersion,
                actorContext,
                operation,
                digest,
                scopeKey));
  }

  private RepositoryAuthorizationPolicySyncResult synchronize(
      Session session,
      RepositoryAuthorizationPolicySnapshot desired,
      long expectedCurrentPolicyVersion,
      GitAccessContext actor,
      String operationId,
      String digest,
      String scopeKey) {
    requireActiveActor(session, actor.principalId());
    SecurityVersionEntity repositoryVersion =
        session.find(SecurityVersionEntity.class, scopeKey, LockModeType.PESSIMISTIC_WRITE);
    if (repositoryVersion == null) {
      throw new SecurityPolicyConfigurationException(
          "repository security generation row is missing after initialization");
    }

    String policyId = managedId("policy", desired.repositoryName(), desired.source(), "head");
    SecurityManagedPolicyEntity head =
        session.find(SecurityManagedPolicyEntity.class, policyId, LockModeType.PESSIMISTIC_WRITE);
    List<SecurityRepositoryGrantEntity> repositoryGrants =
        session
            .createSelectionQuery(
                "FROM SecurityRepositoryGrant g WHERE g.repositoryName = :repositoryName",
                SecurityRepositoryGrantEntity.class)
            .setParameter("repositoryName", desired.repositoryName().value())
            .getResultList();
    List<SecurityRefRuleEntity> repositoryRules =
        session
            .createSelectionQuery(
                "FROM SecurityRefRule r WHERE r.repositoryName = :repositoryName",
                SecurityRefRuleEntity.class)
            .setParameter("repositoryName", desired.repositoryName().value())
            .getResultList();

    List<SecurityRepositoryGrantEntity> managedGrants =
        repositoryGrants.stream().filter(grant -> managedBy(grant, desired.source())).toList();
    List<SecurityRefRuleEntity> managedRules =
        repositoryRules.stream().filter(rule -> managedBy(rule, desired.source())).toList();
    int outsideGrantCount = repositoryGrants.size() - managedGrants.size();
    int outsideRuleCount = repositoryRules.size() - managedRules.size();
    long currentPolicyVersion = head == null ? 0 : head.getPolicyVersion();
    long currentGeneration = repositoryVersion.getVersionValue();

    RepositoryAuthorizationPolicySyncResult preliminary =
        preliminaryResult(
            desired,
            expectedCurrentPolicyVersion,
            digest,
            head,
            managedGrants,
            managedRules,
            repositoryGrants,
            outsideGrantCount,
            outsideRuleCount,
            currentPolicyVersion,
            currentGeneration);
    if (preliminary != null) {
      return preliminary;
    }

    validateSubjects(session, desired);
    long generation = nextVersion(currentGeneration, "policy generation");
    Instant now = clock.instant();
    MutationCounts counts =
        applyManagedEntries(
            session, desired, managedGrants, managedRules, actor, now, generation);
    repositoryVersion.setVersionValue(generation);
    updateHead(session, head, policyId, desired, actor, operationId, digest, generation, now);
    return result(
        RepositoryAuthorizationPolicySyncStatus.APPLIED,
        "POLICY_APPLIED",
        desired,
        currentPolicyVersion,
        desired.policyVersion(),
        generation,
        digest,
        counts.created(),
        counts.updated(),
        counts.deleted(),
        outsideGrantCount,
        outsideRuleCount);
  }

  private static RepositoryAuthorizationPolicySyncResult preliminaryResult(
      RepositoryAuthorizationPolicySnapshot desired,
      long expectedCurrentPolicyVersion,
      String digest,
      SecurityManagedPolicyEntity head,
      List<SecurityRepositoryGrantEntity> managedGrants,
      List<SecurityRefRuleEntity> managedRules,
      List<SecurityRepositoryGrantEntity> repositoryGrants,
      int outsideGrantCount,
      int outsideRuleCount,
      long currentPolicyVersion,
      long currentGeneration) {
    if (head == null && (!managedGrants.isEmpty() || !managedRules.isEmpty())) {
      return unchanged(
          RepositoryAuthorizationPolicySyncStatus.CONFLICT,
          "MANAGED_POLICY_HEAD_MISSING",
          desired,
          currentPolicyVersion,
          currentGeneration,
          digest,
          outsideGrantCount,
          outsideRuleCount);
    }
    if (desired.ownershipMode() == RepositoryPolicyOwnershipMode.EXCLUSIVE_REPOSITORY
        && (outsideGrantCount > 0 || outsideRuleCount > 0)) {
      return unchanged(
          RepositoryAuthorizationPolicySyncStatus.CONFLICT,
          "OUTSIDE_NAMESPACE_POLICY_PRESENT",
          desired,
          currentPolicyVersion,
          currentGeneration,
          digest,
          outsideGrantCount,
          outsideRuleCount);
    }
    if (desired.policyVersion() < currentPolicyVersion) {
      return unchanged(
          RepositoryAuthorizationPolicySyncStatus.STALE,
          "STALE_POLICY_VERSION",
          desired,
          currentPolicyVersion,
          currentGeneration,
          digest,
          outsideGrantCount,
          outsideRuleCount);
    }
    if (desired.policyVersion() == currentPolicyVersion) {
      if (head != null
          && head.getContentDigest().equals(digest)
          && head.getOwnershipMode() == desired.ownershipMode()) {
        return unchanged(
            RepositoryAuthorizationPolicySyncStatus.NO_OP,
            "POLICY_ALREADY_ACTIVE",
            desired,
            currentPolicyVersion,
            currentGeneration,
            digest,
            outsideGrantCount,
            outsideRuleCount);
      }
      return unchanged(
          RepositoryAuthorizationPolicySyncStatus.CONFLICT,
          "POLICY_VERSION_DIGEST_CONFLICT",
          desired,
          currentPolicyVersion,
          currentGeneration,
          digest,
          outsideGrantCount,
          outsideRuleCount);
    }
    if (expectedCurrentPolicyVersion != currentPolicyVersion) {
      return unchanged(
          RepositoryAuthorizationPolicySyncStatus.CONFLICT,
          "EXPECTED_POLICY_VERSION_MISMATCH",
          desired,
          currentPolicyVersion,
          currentGeneration,
          digest,
          outsideGrantCount,
          outsideRuleCount);
    }
    if (hasOutsideGrantSemanticConflict(repositoryGrants, desired)) {
      return unchanged(
          RepositoryAuthorizationPolicySyncStatus.CONFLICT,
          "GRANT_SEMANTIC_CONFLICT",
          desired,
          currentPolicyVersion,
          currentGeneration,
          digest,
          outsideGrantCount,
          outsideRuleCount);
    }
    return null;
  }

  private MutationCounts applyManagedEntries(
      Session session,
      RepositoryAuthorizationPolicySnapshot desired,
      List<SecurityRepositoryGrantEntity> existingGrants,
      List<SecurityRefRuleEntity> existingRules,
      GitAccessContext actor,
      Instant now,
      long generation) {
    Map<String, SecurityRepositoryGrantEntity> grantsByKey = grantMap(existingGrants);
    Map<String, SecurityRefRuleEntity> rulesByKey = ruleMap(existingRules);
    Set<String> desiredGrantKeys =
        desired.grants().stream()
            .map(DesiredRepositoryGrant::entryKey)
            .collect(Collectors.toSet());
    Set<String> desiredRuleKeys =
        desired.refRules().stream()
            .map(DesiredRepositoryRefRule::entryKey)
            .collect(Collectors.toSet());

    int deleted = removeObsolete(session, existingGrants, existingRules, desiredGrantKeys, desiredRuleKeys);
    int created = 0;
    int updated = 0;

    List<DesiredRepositoryGrant> orderedGrants =
        desired.grants().stream()
            .sorted(Comparator.comparing(DesiredRepositoryGrant::entryKey))
            .toList();
    for (DesiredRepositoryGrant desiredGrant : orderedGrants) {
      SecurityRepositoryGrantEntity entity = grantsByKey.get(desiredGrant.entryKey());
      boolean isNew = entity == null;
      if (isNew) {
        entity = new SecurityRepositoryGrantEntity();
        entity.setGrantId(
            managedId("grant", desired.repositoryName(), desired.source(), desiredGrant.entryKey()));
        rejectPolicyIdCollision(session, SecurityRepositoryGrantEntity.class, entity.getGrantId());
        entity.setRepositoryName(desired.repositoryName().value());
        entity.setCreatedAt(now);
        entity.setCreatedBy(actor.principalId());
      }
      entity.setSubjectType(desiredGrant.subject().type());
      entity.setSubjectId(desiredGrant.subject().id());
      entity.setPermission(desiredGrant.permission());
      entity.setEffect(desiredGrant.effect());
      setManaged(entity, desired.source(), desiredGrant.entryKey(), desired.policyVersion());
      entity.setSecurityVersion(generation);
      if (isNew) {
        session.persist(entity);
        created++;
      } else {
        updated++;
      }
    }

    List<DesiredRepositoryRefRule> orderedRules =
        desired.refRules().stream()
            .sorted(Comparator.comparing(DesiredRepositoryRefRule::entryKey))
            .toList();
    for (DesiredRepositoryRefRule desiredRule : orderedRules) {
      SecurityRefRuleEntity entity = rulesByKey.get(desiredRule.entryKey());
      boolean isNew = entity == null;
      if (isNew) {
        entity = new SecurityRefRuleEntity();
        entity.setRuleId(
            managedId("rule", desired.repositoryName(), desired.source(), desiredRule.entryKey()));
        rejectPolicyIdCollision(session, SecurityRefRuleEntity.class, entity.getRuleId());
        entity.setRepositoryName(desired.repositoryName().value());
        entity.setCreatedAt(now);
        entity.setCreatedBy(actor.principalId());
      }
      entity.setRefPattern(desiredRule.refPattern());
      entity.setPermission(desiredRule.permission());
      entity.setEffect(desiredRule.effect());
      entity.setPriority(desiredRule.priority());
      entity.setSubjectType(desiredRule.subject() == null ? null : desiredRule.subject().type());
      entity.setSubjectId(desiredRule.subject() == null ? null : desiredRule.subject().id());
      entity.setEnabled(true);
      setManaged(entity, desired.source(), desiredRule.entryKey(), desired.policyVersion());
      entity.setSecurityVersion(generation);
      if (isNew) {
        session.persist(entity);
        created++;
      } else {
        updated++;
      }
    }
    return new MutationCounts(created, updated, deleted);
  }

  private static int removeObsolete(
      Session session,
      List<SecurityRepositoryGrantEntity> existingGrants,
      List<SecurityRefRuleEntity> existingRules,
      Set<String> desiredGrantKeys,
      Set<String> desiredRuleKeys) {
    int deleted = 0;
    for (SecurityRepositoryGrantEntity existing : existingGrants) {
      if (!desiredGrantKeys.contains(existing.getManagedEntryKey())) {
        session.remove(existing);
        deleted++;
      }
    }
    for (SecurityRefRuleEntity existing : existingRules) {
      if (!desiredRuleKeys.contains(existing.getManagedEntryKey())) {
        session.remove(existing);
        deleted++;
      }
    }
    if (deleted > 0) {
      session.flush();
    }
    return deleted;
  }

  private void updateHead(
      Session session,
      SecurityManagedPolicyEntity head,
      String policyId,
      RepositoryAuthorizationPolicySnapshot desired,
      GitAccessContext actor,
      String operationId,
      String digest,
      long generation,
      Instant now) {
    SecurityManagedPolicyEntity entity = head;
    boolean isNew = entity == null;
    if (isNew) {
      entity = new SecurityManagedPolicyEntity();
      entity.setPolicyId(policyId);
      entity.setRepositoryName(desired.repositoryName().value());
      entity.setManagedSourceId(desired.source().sourceId());
      entity.setManagedSourceInstanceId(desired.source().sourceInstanceId());
      entity.setCreatedAt(now);
      entity.setCreatedByPrincipalId(actor.principalId());
    }
    entity.setOwnershipMode(desired.ownershipMode());
    entity.setPolicyVersion(desired.policyVersion());
    entity.setContentDigest(digest);
    entity.setPolicyGeneration(generation);
    entity.setUpdatedAt(now);
    entity.setUpdatedByPrincipalId(actor.principalId());
    entity.setLastOperationId(operationId);
    entity.setLastCorrelationId(actor.correlationId());
    if (isNew) {
      session.persist(entity);
    }
  }

  private static void validateDesiredSemantics(
      RepositoryAuthorizationPolicySnapshot desired) {
    Set<String> grantSemantics = new HashSet<>();
    for (DesiredRepositoryGrant grant : desired.grants()) {
      String semantic =
          grant.subject().type()
              + "\u0000"
              + grant.subject().id()
              + "\u0000"
              + grant.permission()
              + "\u0000"
              + grant.effect();
      if (!grantSemantics.add(semantic)) {
        throw new IllegalArgumentException("managed policy contains a duplicate grant semantic");
      }
    }

    Set<String> rulePrecedence = new HashSet<>();
    for (DesiredRepositoryRefRule rule : desired.refRules()) {
      String semantic =
          rule.refPattern()
              + "\u0000"
              + rule.permission()
              + "\u0000"
              + rule.priority()
              + "\u0000"
              + (rule.subject() == null
                  ? "GLOBAL"
                  : rule.subject().type() + "\u0000" + rule.subject().id());
      if (!rulePrecedence.add(semantic)) {
        throw new IllegalArgumentException(
            "managed policy contains ambiguous equal-precedence ref rules");
      }
    }
  }

  private static void validateSubjects(
      Session session, RepositoryAuthorizationPolicySnapshot desired) {
    Set<String> principalIds = new HashSet<>();
    Set<String> groupIds = new HashSet<>();
    desired.grants().forEach(grant -> collect(grant.subject(), principalIds, groupIds));
    desired.refRules().stream()
        .map(DesiredRepositoryRefRule::subject)
        .filter(Objects::nonNull)
        .forEach(subject -> collect(subject, principalIds, groupIds));

    if (!principalIds.isEmpty()) {
      long activePrincipals =
          session
              .createSelectionQuery(
                  "SELECT p.principalId FROM SecurityPrincipal p "
                      + "WHERE p.principalId IN :principalIds AND p.status = :status",
                  String.class)
              .setParameter("principalIds", principalIds)
              .setParameter("status", SecurityPrincipalStatus.ACTIVE)
              .getResultList()
              .stream()
              .distinct()
              .count();
      if (activePrincipals != principalIds.size()) {
        throw new SecurityPolicyConfigurationException(
            "managed policy references a missing or inactive principal");
      }
    }
    if (!groupIds.isEmpty()) {
      long activeGroups =
          session
              .createSelectionQuery(
                  "SELECT g.groupId FROM SecurityGroup g "
                      + "WHERE g.groupId IN :groupIds AND g.status = :status",
                  String.class)
              .setParameter("groupIds", groupIds)
              .setParameter("status", SecurityGroupStatus.ACTIVE)
              .getResultList()
              .stream()
              .distinct()
              .count();
      if (activeGroups != groupIds.size()) {
        throw new SecurityPolicyConfigurationException(
            "managed policy references a missing or inactive group");
      }
    }
  }

  private static void collect(
      SecuritySubject subject, Set<String> principalIds, Set<String> groupIds) {
    switch (subject.type()) {
      case PRINCIPAL -> principalIds.add(subject.id());
      case GROUP -> groupIds.add(subject.id());
    }
  }

  private static void requireActiveActor(Session session, String principalId) {
    SecurityPrincipalEntity actor = session.find(SecurityPrincipalEntity.class, principalId);
    if (actor == null || actor.getStatus() != SecurityPrincipalStatus.ACTIVE) {
      throw new SecurityAuthenticationException(
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE);
    }
  }

  private static boolean hasOutsideGrantSemanticConflict(
      List<SecurityRepositoryGrantEntity> repositoryGrants,
      RepositoryAuthorizationPolicySnapshot desired) {
    for (DesiredRepositoryGrant desiredGrant : desired.grants()) {
      boolean conflict =
          repositoryGrants.stream()
              .filter(grant -> !managedBy(grant, desired.source()))
              .anyMatch(grant -> sameSemantic(grant, desiredGrant));
      if (conflict) {
        return true;
      }
    }
    return false;
  }

  private static boolean sameSemantic(
      SecurityRepositoryGrantEntity entity, DesiredRepositoryGrant desired) {
    return entity.getSubjectType() == desired.subject().type()
        && entity.getSubjectId().equals(desired.subject().id())
        && entity.getPermission() == desired.permission()
        && entity.getEffect() == desired.effect();
  }

  private static Map<String, SecurityRepositoryGrantEntity> grantMap(
      List<SecurityRepositoryGrantEntity> grants) {
    Map<String, SecurityRepositoryGrantEntity> result = new HashMap<>();
    for (SecurityRepositoryGrantEntity grant : grants) {
      if (result.put(grant.getManagedEntryKey(), grant) != null) {
        throw new SecurityPolicyConfigurationException(
            "managed grant entry key is not unique");
      }
    }
    return result;
  }

  private static Map<String, SecurityRefRuleEntity> ruleMap(
      List<SecurityRefRuleEntity> rules) {
    Map<String, SecurityRefRuleEntity> result = new HashMap<>();
    for (SecurityRefRuleEntity rule : rules) {
      if (result.put(rule.getManagedEntryKey(), rule) != null) {
        throw new SecurityPolicyConfigurationException(
            "managed ref rule entry key is not unique");
      }
    }
    return result;
  }

  private static boolean managedBy(
      SecurityRepositoryGrantEntity entity, ManagedPolicySource source) {
    return source.sourceId().equals(entity.getManagedSourceId())
        && source.sourceInstanceId().equals(entity.getManagedSourceInstanceId());
  }

  private static boolean managedBy(
      SecurityRefRuleEntity entity, ManagedPolicySource source) {
    return source.sourceId().equals(entity.getManagedSourceId())
        && source.sourceInstanceId().equals(entity.getManagedSourceInstanceId());
  }

  private static void setManaged(
      SecurityRepositoryGrantEntity entity,
      ManagedPolicySource source,
      String entryKey,
      long policyVersion) {
    entity.setManagedSourceId(source.sourceId());
    entity.setManagedSourceInstanceId(source.sourceInstanceId());
    entity.setManagedEntryKey(entryKey);
    entity.setManagedPolicyVersion(policyVersion);
  }

  private static void setManaged(
      SecurityRefRuleEntity entity,
      ManagedPolicySource source,
      String entryKey,
      long policyVersion) {
    entity.setManagedSourceId(source.sourceId());
    entity.setManagedSourceInstanceId(source.sourceInstanceId());
    entity.setManagedEntryKey(entryKey);
    entity.setManagedPolicyVersion(policyVersion);
  }

  private static <T> void rejectPolicyIdCollision(
      Session session, Class<T> entityType, String id) {
    if (session.find(entityType, id) != null) {
      throw new SecurityPolicyConfigurationException(
          "deterministic managed policy ID collides with an existing row");
    }
  }

  private void ensureVersionScope(String scopeKey) {
    try {
      sessionFactory.inTransaction(
          session -> {
            if (session.find(SecurityVersionEntity.class, scopeKey) == null) {
              SecurityVersionEntity version = new SecurityVersionEntity();
              version.setScopeKey(scopeKey);
              version.setVersionValue(0);
              session.persist(version);
              session.flush();
            }
          });
    } catch (RuntimeException failure) {
      if (!causedByConstraintViolation(failure)) {
        throw failure;
      }
    }
  }

  private static boolean causedByConstraintViolation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ConstraintViolationException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static String contentDigest(RepositoryAuthorizationPolicySnapshot desired) {
    MessageDigest digest = sha256();
    digestField(digest, desired.repositoryName().value());
    digestField(digest, desired.source().sourceId());
    digestField(digest, desired.source().sourceInstanceId());
    digestField(digest, desired.ownershipMode().name());
    desired.grants().stream()
        .sorted(Comparator.comparing(DesiredRepositoryGrant::entryKey))
        .forEach(grant -> digestGrant(digest, grant));
    desired.refRules().stream()
        .sorted(Comparator.comparing(DesiredRepositoryRefRule::entryKey))
        .forEach(rule -> digestRule(digest, rule));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void digestGrant(MessageDigest digest, DesiredRepositoryGrant grant) {
    digestField(digest, "GRANT");
    digestField(digest, grant.entryKey());
    digestField(digest, grant.subject().type().name());
    digestField(digest, grant.subject().id());
    digestField(digest, grant.permission().name());
    digestField(digest, grant.effect().name());
  }

  private static void digestRule(MessageDigest digest, DesiredRepositoryRefRule rule) {
    digestField(digest, "REF_RULE");
    digestField(digest, rule.entryKey());
    digestField(digest, rule.refPattern());
    digestField(digest, rule.permission().name());
    digestField(digest, rule.effect().name());
    digestField(digest, Integer.toString(rule.priority()));
    digestField(digest, rule.subject() == null ? "GLOBAL" : rule.subject().type().name());
    digestField(digest, rule.subject() == null ? "GLOBAL" : rule.subject().id());
  }

  private static String managedId(
      String kind, RepositoryName repository, ManagedPolicySource source, String entryKey) {
    MessageDigest digest = sha256();
    digestField(digest, kind);
    digestField(digest, repository.value());
    digestField(digest, source.sourceId());
    digestField(digest, source.sourceInstanceId());
    digestField(digest, entryKey);
    String prefix =
        switch (kind) {
          case "policy" -> "mp-";
          case "grant" -> "mg-";
          case "rule" -> "mr-";
          default -> throw new IllegalArgumentException("unknown managed ID kind");
        };
    return prefix + HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void digestField(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String versionScope(RepositoryName repositoryName) {
    return VERSION_SCOPE_PREFIX + repositoryName.value();
  }

  private static long nextVersion(long current, String name) {
    if (current == Long.MAX_VALUE) {
      throw new SecurityPolicyConfigurationException(name + " cannot be incremented");
    }
    return current + 1;
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain 1 to " + maximumLength + " characters");
    }
    if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          name + " must not contain surrounding whitespace or control characters");
    }
    return value;
  }

  private static RepositoryAuthorizationPolicySyncResult unchanged(
      RepositoryAuthorizationPolicySyncStatus status,
      String reasonCode,
      RepositoryAuthorizationPolicySnapshot desired,
      long currentPolicyVersion,
      long generation,
      String digest,
      int outsideGrantCount,
      int outsideRuleCount) {
    return result(
        status,
        reasonCode,
        desired,
        currentPolicyVersion,
        currentPolicyVersion,
        generation,
        digest,
        0,
        0,
        0,
        outsideGrantCount,
        outsideRuleCount);
  }

  private static RepositoryAuthorizationPolicySyncResult result(
      RepositoryAuthorizationPolicySyncStatus status,
      String reasonCode,
      RepositoryAuthorizationPolicySnapshot desired,
      long previousPolicyVersion,
      long activePolicyVersion,
      long generation,
      String digest,
      int created,
      int updated,
      int deleted,
      int outsideGrantCount,
      int outsideRuleCount) {
    return new RepositoryAuthorizationPolicySyncResult(
        status,
        reasonCode,
        desired.repositoryName(),
        desired.source(),
        previousPolicyVersion,
        activePolicyVersion,
        generation,
        digest,
        created,
        updated,
        deleted,
        outsideGrantCount,
        outsideRuleCount);
  }

  private record MutationCounts(int created, int updated, int deleted) {}
}
