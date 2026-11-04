/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import java.time.Instant;
import java.util.List;
import org.eclipse.jgit.lib.Constants;
import org.hibernate.Session;

/** Complete persisted snapshots keep durable-batch assertions independent of bootstrap history. */
final class ReflogBatchDatabaseAssertions {
  private ReflogBatchDatabaseAssertions() {}

  record ReflogSnapshot(
      Long id,
      Long version,
      String deliveryId,
      String refName,
      String refNameKey,
      String oldId,
      String newId,
      String whoName,
      String whoEmail,
      Instant when,
      String message) {}

  static List<ReflogSnapshot> reflogSnapshot(
      HibernateSessionFactoryProvider provider, String repository) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "from GitReflogEntity r where r.repositoryName = :repo order by r.id",
              GitReflogEntity.class)
          .setParameter("repo", repository)
          .getResultList()
          .stream()
          .map(
              row ->
                  new ReflogSnapshot(
                      row.getId(),
                      row.getVersion(),
                      row.getDeliveryId(),
                      row.getRefName(),
                      row.getRefNameKey(),
                      row.getOldId(),
                      row.getNewId(),
                      row.getWhoName(),
                      row.getWhoEmail(),
                      row.getWhen(),
                      row.getMessage()))
          .toList();
    }
  }

  static List<ReflogSnapshot> bootstrapSnapshot(
      HibernateSessionFactoryProvider provider, String repository) {
    List<ReflogSnapshot> bootstrap = reflogSnapshot(provider, repository);
    assertFalse(bootstrap.isEmpty(), "repository creation must retain bootstrap reflog history");
    assertTrue(bootstrap.stream().anyMatch(row -> row.refName().equals(Constants.HEAD)));
    assertTrue(bootstrap.stream().allMatch(row -> row.deliveryId() == null));
    return bootstrap;
  }

  static void assertBootstrapPreserved(
      HibernateSessionFactoryProvider provider,
      String repository,
      List<ReflogSnapshot> bootstrap,
      int deliveryCount) {
    List<ReflogSnapshot> rows = reflogSnapshot(provider, repository);
    assertEquals(bootstrap.size() + deliveryCount, rows.size());
    assertEquals(bootstrap, rows.stream().filter(row -> row.deliveryId() == null).toList());
  }
}
