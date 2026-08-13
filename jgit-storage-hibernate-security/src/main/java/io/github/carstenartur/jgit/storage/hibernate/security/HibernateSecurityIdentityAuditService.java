/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityIdentityAuditEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Hibernate-backed append-only credential lifecycle audit service. */
public final class HibernateSecurityIdentityAuditService
    implements SecurityIdentityAuditRecorder {

  private static final int MAX_QUERY_LIMIT = 1000;
  private static final int MAX_PRINCIPAL_ID_LENGTH = 128;
  private static final int MAX_CONTEXT_ID_LENGTH = 256;
  private static final int MAX_CREDENTIAL_ID_LENGTH = 128;

  private final SessionFactory sessionFactory;
  private final Clock clock;
  private final Supplier<String> auditIdSupplier;

  /** Create a persistent recorder using the system UTC clock and random opaque identifiers. */
  public HibernateSecurityIdentityAuditService(SessionFactory sessionFactory) {
    this(sessionFactory, Clock.systemUTC(), () -> UUID.randomUUID().toString());
  }

  HibernateSecurityIdentityAuditService(
      SessionFactory sessionFactory, Clock clock, Supplier<String> auditIdSupplier) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.auditIdSupplier = Objects.requireNonNull(auditIdSupplier, "auditIdSupplier");
  }

  @Override
  public void record(SecurityIdentityAuditRecord record) {
    Objects.requireNonNull(record, "record");
    try {
      sessionFactory.inTransaction(session -> persist(session, record));
    } catch (SecurityIdentityAuditPersistenceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SecurityIdentityAuditPersistenceException(
          "Could not append credential lifecycle audit evidence", exception);
    }
  }

  /**
   * Append audit evidence in the caller's active transaction.
   *
   * <p>This is used by credential-management and authentication services so state transitions and
   * their success/failure evidence commit or roll back together.
   */
  SecurityIdentityAuditEvent record(Session session, SecurityIdentityAuditRecord record) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(record, "record");
    try {
      return persist(session, record);
    } catch (SecurityIdentityAuditPersistenceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SecurityIdentityAuditPersistenceException(
          "Could not append credential lifecycle audit evidence", exception);
    }
  }

  /** Find one event by opaque audit identifier. */
  public Optional<SecurityIdentityAuditEvent> findByAuditId(String auditId) {
    String id = required("auditId", auditId, 64);
    try (Session session = sessionFactory.openSession()) {
      return Optional.ofNullable(session.find(SecurityIdentityAuditEntity.class, id))
          .map(HibernateSecurityIdentityAuditService::toEvent);
    }
  }

  /** Find newest events for one subject principal. */
  public List<SecurityIdentityAuditEvent> findBySubjectPrincipal(
      String principalId, int limit) {
    return find(
        "FROM SecurityIdentityAudit a WHERE a.subjectPrincipalId = :value "
            + "ORDER BY a.occurredAt DESC, a.auditId DESC",
        required("principalId", principalId, MAX_PRINCIPAL_ID_LENGTH),
        limit);
  }

  /** Find newest events initiated by one administrative actor. */
  public List<SecurityIdentityAuditEvent> findByActorPrincipal(
      String principalId, int limit) {
    return find(
        "FROM SecurityIdentityAudit a WHERE a.actorPrincipalId = :value "
            + "ORDER BY a.occurredAt DESC, a.auditId DESC",
        required("principalId", principalId, MAX_PRINCIPAL_ID_LENGTH),
        limit);
  }

  /** Find newest events belonging to one request correlation identifier. */
  public List<SecurityIdentityAuditEvent> findByCorrelationId(
      String correlationId, int limit) {
    return find(
        "FROM SecurityIdentityAudit a WHERE a.correlationId = :value "
            + "ORDER BY a.occurredAt DESC, a.auditId DESC",
        required("correlationId", correlationId, MAX_CONTEXT_ID_LENGTH),
        limit);
  }

  /** Find newest events for one non-secret internal credential identifier. */
  public List<SecurityIdentityAuditEvent> findByCredentialId(
      String credentialId, int limit) {
    return find(
        "FROM SecurityIdentityAudit a WHERE a.credentialId = :value "
            + "ORDER BY a.occurredAt DESC, a.auditId DESC",
        required("credentialId", credentialId, MAX_CREDENTIAL_ID_LENGTH),
        limit);
  }

  private List<SecurityIdentityAuditEvent> find(String hql, String value, int limit) {
    int boundedLimit = queryLimit(limit);
    try (Session session = sessionFactory.openSession()) {
      return session
          .createSelectionQuery(hql, SecurityIdentityAuditEntity.class)
          .setParameter("value", value)
          .setMaxResults(boundedLimit)
          .getResultList()
          .stream()
          .map(HibernateSecurityIdentityAuditService::toEvent)
          .toList();
    }
  }

  private SecurityIdentityAuditEvent persist(
      Session session, SecurityIdentityAuditRecord record) {
    String auditId = required("generated auditId", auditIdSupplier.get(), 64);
    Instant occurredAt = Instant.now(clock);
    SecurityIdentityAuditEntity entity = toEntity(auditId, occurredAt, record);
    session.persist(entity);
    return new SecurityIdentityAuditEvent(auditId, occurredAt, record);
  }

  private static SecurityIdentityAuditEntity toEntity(
      String auditId, Instant occurredAt, SecurityIdentityAuditRecord record) {
    SecurityIdentityAuditEntity entity = new SecurityIdentityAuditEntity();
    entity.setAuditId(auditId);
    entity.setOccurredAt(occurredAt);
    entity.setOperation(record.operation());
    entity.setOutcome(record.outcome());
    entity.setActorPrincipalId(record.actorPrincipalId());
    entity.setSubjectPrincipalId(record.subjectPrincipalId());
    entity.setAuthenticationMethod(record.authenticationMethod());
    entity.setSessionId(record.sessionId());
    entity.setCorrelationId(record.correlationId());
    entity.setRemoteAddressHash(record.remoteAddressHash());
    entity.setCredentialKind(record.credentialKind());
    entity.setCredentialId(record.credentialId());
    entity.setReasonCode(record.reasonCode());
    entity.setFailureType(record.failureType());
    return entity;
  }

  private static SecurityIdentityAuditEvent toEvent(SecurityIdentityAuditEntity entity) {
    SecurityIdentityAuditRecord record =
        new SecurityIdentityAuditRecord(
            entity.getOperation(),
            entity.getOutcome(),
            entity.getActorPrincipalId(),
            entity.getSubjectPrincipalId(),
            entity.getAuthenticationMethod(),
            entity.getSessionId(),
            entity.getCorrelationId(),
            entity.getRemoteAddressHash(),
            entity.getCredentialKind(),
            entity.getCredentialId(),
            entity.getReasonCode(),
            entity.getFailureType());
    return new SecurityIdentityAuditEvent(entity.getAuditId(), entity.getOccurredAt(), record);
  }

  private static int queryLimit(int limit) {
    if (limit < 1 || limit > MAX_QUERY_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_QUERY_LIMIT);
    }
    return limit;
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }
}
