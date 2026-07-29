/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class MixedStagingRollbackH2Test {

  @Test
  void removesLocalAndLegacyExtensionsForTheSameLogicalPack() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "mixed-rollback")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      String packName = baseName(description);

      try (DfsOutputStream stream = database.writeFile(description, PackExt.PACK)) {
        stream.write(new byte[] {1, 2, 3}, 0, 3);
      }
      description.addFileExt(PackExt.PACK);
      description.addFileExt(PackExt.INDEX);
      persistLegacyIndex(provider, packName);

      database.rollbackPack(List.of(description));

      assertEquals(0, database.stagedExtensionCount());
      assertEquals(0L, rows(provider, packName));
    }
  }

  private static void persistLegacyIndex(
      HibernateSessionFactoryProvider provider, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName("mixed-rollback");
      entity.setPackName(packName);
      entity.setPackExtension("idx");
      entity.setData(new byte[] {4});
      entity.setFileSize(1);
      entity.setCommitted(false);
      entity.setCreatedAt(Instant.now());
      entity.setWriteToken(UUID.randomUUID().toString());
      entity.setWriteLeaseUntil(Instant.now().plusSeconds(300));
      session.persist(entity);
      session.getTransaction().commit();
    }
  }

  private static long rows(HibernateSessionFactoryProvider provider, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name",
              Long.class)
          .setParameter("repo", "mixed-rollback")
          .setParameter("name", packName)
          .getSingleResult();
    }
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:mixed-staging-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }
}
