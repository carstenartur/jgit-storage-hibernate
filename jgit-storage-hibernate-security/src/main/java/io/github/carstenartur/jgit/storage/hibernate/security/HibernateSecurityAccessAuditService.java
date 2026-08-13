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
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessAuditEntity;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;

/**
 * Hibernate-backed append-only access-audit recorder with bounded repository, principal and
 * correlation queries.
 */
public final class HibernateSecurityAccessAuditService implements SecurityAccessAuditRecorder {

  private static final int MAX_QUERY_LIMIT = 1000;

  private final SessionFactory sessionFactory;
  private final Clock clock;
  private final Supplier<String> auditIdSupplier;

  /**
   * Create a persistent recorder using UTC timestamps and random UUID audit IDs.
   *
   * @param sessionFactory Hibernate session factory containing Security entities
   */
  public HibernateSecurityAccessAuditService(SessionFactory sessionFactory) {
    this(sessionFactory, Clock.systemUTC(), () -> UUID.randomUUID().toString());
  }

  HibernateSecurityAccessAuditService(
      SessionFactory sessionFactory, Clock clock, Supplier<String> auditIdSupplier) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.auditIdSupplier = Objects.requireNonNull(auditIdSupplier, "auditIdSupplier");
  }

  @Override
  public void record(SecurityAccessAuditRecord record) {
    SecurityAccessAuditEvent event =
        new SecurityAccessAuditEvent(
            Objects.requireNonNull(auditIdSupplier.get(), "auditIdSupplier result"),
            clock.instant(),
            Objects.requireNonNull(record, "record"));
    SecurityAccessAuditEntity entity = toEntity(event);
    sessionFactory.inTransaction(session -> session.persist(entity));
  }

  /** Find one event by its opaque audit ID. */
  public Optional<SecurityAccessAuditEvent> findByAuditId(String auditId) {
    String id = required("auditId", auditId, 64);
    SecurityAccessAuditEntity entity =
        sessionFactory.fromTransaction(
            session -> session.find(SecurityAccessAuditEntity.class, id));
    return Optional.ofNullable(entity).map(HibernateSecurityAccessAuditService::toEvent);
  }

  /** Return the newest events for one logical repository. */
  public List<SecurityAccessAuditEvent> findByRepository(
      RepositoryName repositoryName, int limit) {
    RepositoryName repository = Objects.requireNonNull(repositoryName, "repositoryName");
    return sessionFactory.fromTransaction(
        session ->
            session
                .createSelectionQuery(
                    "FROM SecurityAccessAudit a WHERE a.repositoryName = :repositoryName "
                        + "ORDER BY a.occurredAt DESC, a.auditId DESC",
                    SecurityAccessAuditEntity.class)
                .setParameter("repositoryName", repository.value())
                .setMaxResults(limit(limit))
                .getResultList()
                .stream()
                .map(HibernateSecurityAccessAuditService::toEvent)
                .toList());
  }

  /** Return the newest events for one stable principal ID. */
  public List<SecurityAccessAuditEvent> findByPrincipal(String principalId, int limit) {
    String principal = required("principalId", principalId, 128);
    return sessionFactory.fromTransaction(
        session ->
            session
                .createSelectionQuery(
                    "FROM SecurityAccessAudit a WHERE a.principalId = :principalId "
                        + "ORDER BY a.occurredAt DESC, a.auditId DESC",
                    SecurityAccessAuditEntity.class)
                .setParameter("principalId", principal)
                .setMaxResults(limit(limit))
                .getResultList()
                .stream()
                .map(HibernateSecurityAccessAuditService::toEvent)
                .toList());
  }

  /** Return the newest events for one request or operation correlation ID. */
  public List<SecurityAccessAuditEvent> findByCorrelationId(
      String correlationId, int limit) {
    String correlation = required("correlationId", correlationId, 256);
    return sessionFactory.fromTransaction(
        session ->
            session
                .createSelectionQuery(
                    "FROM SecurityAccessAudit a WHERE a.correlationId = :correlationId "
                        + "ORDER BY a.occurredAt DESC, a.auditId DESC",
                    SecurityAccessAuditEntity.class)
                .setParameter("correlationId", correlation)
                .setMaxResults(limit(limit))
                .getResultList()
                .stream()
                .map(HibernateSecurityAccessAuditService::toEvent)
                .toList());
  }

  private static SecurityAccessAuditEntity toEntity(SecurityAccessAuditEvent event) {
    SecurityAccessAuditRecord record = event.record();
    SecurityAccessAuditEntity entity = new SecurityAccessAuditEntity();
    entity.setAuditId(event.auditId());
    entity.setOccurredAt(event.occurredAt());
    entity.setPrincipalId(record.principalId());
    entity.setAuthenticationMethod(record.authenticationMethod());
    entity.setSessionId(record.sessionId());
    entity.setCorrelationId(record.correlationId());
    entity.setRepositoryName(record.repositoryName().value());
    entity.setOperation(record.operation());
    entity.setRefName(record.refName());
    entity.setOldObjectId(record.oldObjectId());
    entity.setNewObjectId(record.newObjectId());
    entity.setOutcome(record.outcome());
    entity.setReasonCode(record.reasonCode());
    entity.setEvidenceId(record.evidenceId());
    entity.setPolicyVersion(record.policyVersion());
    entity.setFailureType(record.failureType());
    return entity;
  }

  private static SecurityAccessAuditEvent toEvent(SecurityAccessAuditEntity entity) {
    SecurityAccessAuditRecord record =
        new SecurityAccessAuditRecord(
            entity.getPrincipalId(),
            entity.getAuthenticationMethod(),
            entity.getSessionId(),
            entity.getCorrelationId(),
            new RepositoryName(entity.getRepositoryName()),
            entity.getOperation(),
            entity.getRefName(),
            entity.getOldObjectId(),
            entity.getNewObjectId(),
            entity.getOutcome(),
            entity.getReasonCode(),
            entity.getEvidenceId(),
            entity.getPolicyVersion(),
            entity.getFailureType());
    return new SecurityAccessAuditEvent(entity.getAuditId(), entity.getOccurredAt(), record);
  }

  private static int limit(int limit) {
    if (limit < 1 || limit > MAX_QUERY_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_QUERY_LIMIT);
    }
    return limit;
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain 1 to " + maximumLength + " characters");
    }
    return value;
  }
}
