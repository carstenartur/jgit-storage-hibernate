/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/** Hibernate-backed external issuer/subject binding with deterministic concurrent provisioning. */
public final class HibernateExternalPrincipalBindingService
    implements ExternalPrincipalBindingService {

  private static final int MAX_PRINCIPAL_ID_LENGTH = 128;

  private final SessionFactory sessionFactory;
  private final Clock clock;
  private final ExternalPrincipalIdGenerator principalIdGenerator;

  /** Create a binding service that assigns random UUID principal IDs. */
  public HibernateExternalPrincipalBindingService(SessionFactory sessionFactory) {
    this(sessionFactory, identity -> UUID.randomUUID().toString());
  }

  /**
   * Create a binding service with a trusted application-owned stable ID strategy.
   *
   * @param sessionFactory Hibernate session factory containing Security entities
   * @param principalIdGenerator trusted host strategy used only for first provisioning
   */
  public HibernateExternalPrincipalBindingService(
      SessionFactory sessionFactory, ExternalPrincipalIdGenerator principalIdGenerator) {
    this(sessionFactory, Clock.systemUTC(), principalIdGenerator);
  }

  HibernateExternalPrincipalBindingService(
      SessionFactory sessionFactory,
      Clock clock,
      ExternalPrincipalIdGenerator principalIdGenerator) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.principalIdGenerator =
        Objects.requireNonNull(principalIdGenerator, "principalIdGenerator");
  }

  @Override
  public ExternalPrincipalBindingResult resolve(
      ExternalPrincipalIdentity identity,
      ExternalPrincipalProvisioningPolicy provisioningPolicy) {
    ExternalPrincipalIdentity externalIdentity = Objects.requireNonNull(identity, "identity");
    ExternalPrincipalProvisioningPolicy policy =
        Objects.requireNonNull(provisioningPolicy, "provisioningPolicy");
    try {
      return sessionFactory.fromTransaction(
          session -> resolve(session, externalIdentity, policy));
    } catch (SecurityAuthenticationException | SecurityPolicyConfigurationException expected) {
      throw expected;
    } catch (RuntimeException failure) {
      if (policy == ExternalPrincipalProvisioningPolicy.CREATE_IF_MISSING
          && causedByConstraintViolation(failure)) {
        ExternalPrincipalBindingResult concurrent =
            sessionFactory.fromTransaction(
                session -> findExisting(session, externalIdentity));
        if (concurrent != null) {
          return concurrent;
        }
      }
      throw failure;
    }
  }

  private ExternalPrincipalBindingResult resolve(
      Session session,
      ExternalPrincipalIdentity identity,
      ExternalPrincipalProvisioningPolicy provisioningPolicy) {
    ExternalPrincipalBindingResult existing = findExisting(session, identity);
    if (existing != null) {
      return existing;
    }
    if (provisioningPolicy == ExternalPrincipalProvisioningPolicy.EXISTING_ONLY) {
      throw new SecurityAuthenticationException(SecurityAuthenticationReason.INVALID_CREDENTIALS);
    }

    String principalId = validatedPrincipalId(principalIdGenerator.generate(identity));
    if (session.find(SecurityPrincipalEntity.class, principalId) != null) {
      throw new SecurityPolicyConfigurationException(
          "generated principalId is already bound to another Security principal");
    }

    Instant now = clock.instant();
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId(principalId);
    principal.setPrincipalType(SecurityPrincipalType.EXTERNAL);
    principal.setLoginName(null);
    principal.setDisplayName(identity.displayName());
    principal.setExternalIssuer(identity.issuer());
    principal.setExternalSubject(identity.subject());
    principal.setStatus(SecurityPrincipalStatus.ACTIVE);
    principal.setCreatedAt(now);
    principal.setUpdatedAt(now);
    principal.setSecurityVersion(1);
    session.persist(principal);
    return result(principal, ExternalPrincipalBindingOutcome.CREATED);
  }

  private ExternalPrincipalBindingResult findExisting(
      Session session, ExternalPrincipalIdentity identity) {
    List<SecurityPrincipalEntity> principals =
        session
            .createSelectionQuery(
                "FROM SecurityPrincipal p WHERE p.externalIssuer = :issuer "
                    + "AND p.externalSubject = :subject",
                SecurityPrincipalEntity.class)
            .setParameter("issuer", identity.issuer())
            .setParameter("subject", identity.subject())
            .setMaxResults(2)
            .getResultList();
    if (principals.size() > 1) {
      throw new SecurityPolicyConfigurationException(
          "external issuer and subject are bound to multiple Security principals");
    }
    if (principals.isEmpty()) {
      return null;
    }

    SecurityPrincipalEntity principal = principals.get(0);
    if (principal.getPrincipalType() != SecurityPrincipalType.EXTERNAL) {
      throw new SecurityPolicyConfigurationException(
          "external issuer and subject are bound to a non-external principal");
    }
    if (principal.getStatus() != SecurityPrincipalStatus.ACTIVE) {
      throw new SecurityAuthenticationException(
          SecurityAuthenticationReason.PRINCIPAL_NOT_ACTIVE);
    }

    boolean updated =
        identity.displayName() != null
            && !Objects.equals(identity.displayName(), principal.getDisplayName());
    if (updated) {
      principal.setDisplayName(identity.displayName());
      principal.setUpdatedAt(clock.instant());
      principal.setSecurityVersion(nextVersion(principal.getSecurityVersion()));
    }
    return result(
        principal,
        updated
            ? ExternalPrincipalBindingOutcome.UPDATED_PROFILE
            : ExternalPrincipalBindingOutcome.RESOLVED);
  }

  private static ExternalPrincipalBindingResult result(
      SecurityPrincipalEntity principal, ExternalPrincipalBindingOutcome outcome) {
    return new ExternalPrincipalBindingResult(
        principal.getPrincipalId(),
        outcome,
        principal.getDisplayName(),
        principal.getSecurityVersion());
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

  private static long nextVersion(long current) {
    if (current == Long.MAX_VALUE) {
      throw new SecurityPolicyConfigurationException(
          "principal security version cannot be incremented");
    }
    return current + 1;
  }

  private static String validatedPrincipalId(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_PRINCIPAL_ID_LENGTH) {
      throw new SecurityPolicyConfigurationException(
          "generated principalId must contain 1 to "
              + MAX_PRINCIPAL_ID_LENGTH
              + " characters");
    }
    if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
      throw new SecurityPolicyConfigurationException(
          "generated principalId must not contain surrounding whitespace or control characters");
    }
    return value;
  }
}
