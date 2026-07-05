/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.objects;

import io.github.carstenartur.jgit.storage.hibernate.internal.entity.GitPackEntity;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Hibernate/JPA backed {@link DfsObjDatabase}. */
public class HibernateObjDatabase extends DfsObjDatabase {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final AtomicInteger packIdCounter = new AtomicInteger((int) (System.nanoTime() & 0x7FFF_FFFF));
  private Set<ObjectId> shallowCommits = Collections.emptySet();

  public HibernateObjDatabase(
      DfsRepository repo, DfsReaderOptions options, SessionFactory sessionFactory, String repositoryName) {
    super(repo, options);
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
  }

  /** Delete all pack rows for this logical repository and clear JGit's pack cache. */
  public void clearAll() {
    try (Session session = sessionFactory.openSession()) {
      session.beginTransaction();
      session
          .createMutationQuery("DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo")
          .setParameter("repo", repositoryName)
          .executeUpdate();
      session.getTransaction().commit();
    }
    clearCache();
  }

  private static String baseName(DfsPackDescription desc) {
    String fileName = desc.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    try (Session session = sessionFactory.openSession()) {
      List<Object[]> rows =
          session
              .createQuery(
                  "SELECT p.packName, p.packExtension, p.fileSize FROM GitPackEntity p WHERE p.repositoryName = :repo",
                  Object[].class)
              .setParameter("repo", repositoryName)
              .getResultList();
      LinkedHashMap<String, DfsPackDescription> descriptions = new LinkedHashMap<>();
      for (Object[] row : rows) {
        String name = (String) row[0];
        String extension = (String) row[1];
        long size = (Long) row[2];
        DfsPackDescription description =
            descriptions.computeIfAbsent(
                name,
                packName ->
                    new DfsPackDescription(
                        getRepository().getDescription(), packName, PackSource.INSERT));
        for (PackExt packExt : PackExt.values()) {
          if (packExt.getExtension().equals(extension)) {
            description.addFileExt(packExt);
            description.setFileSize(packExt, size);
            break;
          }
        }
      }
      return new ArrayList<>(descriptions.values());
    }
  }

  @Override
  protected DfsPackDescription newPack(PackSource source) {
    int id = packIdCounter.incrementAndGet();
    return new DfsPackDescription(
        getRepository().getDescription(), "pack-" + id + "-" + source.name(), source);
  }

  @Override
  protected void commitPackImpl(Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replace)
      throws IOException {
    try (Session session = sessionFactory.openSession()) {
      session.beginTransaction();
      if (replace != null) {
        for (DfsPackDescription description : replace) {
          session
              .createMutationQuery(
                  "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo AND p.packName = :name")
              .setParameter("repo", repositoryName)
              .setParameter("name", baseName(description))
              .executeUpdate();
        }
      }
      session.getTransaction().commit();
    }
    clearCache();
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
    try (Session session = sessionFactory.openSession()) {
      session.beginTransaction();
      for (DfsPackDescription description : descriptions) {
        session
            .createMutationQuery(
                "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo AND p.packName = :name")
            .setParameter("repo", repositoryName)
            .setParameter("name", baseName(description))
            .executeUpdate();
      }
      session.getTransaction().commit();
    } catch (RuntimeException ignored) {
      // Rollback is best-effort and must not mask the original failure.
    }
  }

  @Override
  protected ReadableChannel openFile(DfsPackDescription desc, PackExt ext)
      throws FileNotFoundException, IOException {
    try (Session session = sessionFactory.openSession()) {
      GitPackEntity entity =
          session
              .createQuery(
                  "FROM GitPackEntity p WHERE p.repositoryName = :repo AND p.packName = :name AND p.packExtension = :ext",
                  GitPackEntity.class)
              .setParameter("repo", repositoryName)
              .setParameter("name", baseName(desc))
              .setParameter("ext", ext.getExtension())
              .uniqueResult();
      if (entity == null) {
        throw new FileNotFoundException(desc.getFileName(ext));
      }
      return new ByteArrayReadableChannel(entity.getData());
    }
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription desc, PackExt ext) throws IOException {
    return new HibernatePackOutputStream(sessionFactory, repositoryName, baseName(desc), ext.getExtension());
  }

  @Override
  public Set<ObjectId> getShallowCommits() throws IOException {
    return shallowCommits;
  }

  @Override
  public void setShallowCommits(Set<ObjectId> shallowCommits) {
    this.shallowCommits = shallowCommits;
  }

  @Override
  public long getApproximateObjectCount() {
    try (Session session = sessionFactory.openSession()) {
      Long count =
          session
              .createQuery(
                  "SELECT COUNT(o) FROM GitObjectEntity o WHERE o.repositoryName = :repo", Long.class)
              .setParameter("repo", repositoryName)
              .uniqueResult();
      return count != null ? count : 0;
    }
  }

  static class HibernatePackOutputStream extends DfsOutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final SessionFactory sessionFactory;
    private final String repositoryName;
    private final String packName;
    private final String packExtension;
    private byte[] data;
    private boolean flushed;

    HibernatePackOutputStream(
        SessionFactory sessionFactory, String repositoryName, String packName, String packExtension) {
      this.sessionFactory = sessionFactory;
      this.repositoryName = repositoryName;
      this.packName = packName;
      this.packExtension = packExtension;
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      data = null;
      buffer.write(buf, off, len);
    }

    @Override
    public int read(long position, ByteBuffer dst) {
      byte[] bytes = getData();
      int count = Math.min(dst.remaining(), bytes.length - (int) position);
      if (count == 0) {
        return -1;
      }
      dst.put(bytes, (int) position, count);
      return count;
    }

    byte[] getData() {
      if (data == null) {
        data = buffer.toByteArray();
      }
      return data;
    }

    @Override
    public void flush() {
      if (flushed) {
        return;
      }
      flushed = true;
      byte[] bytes = getData();
      try (Session session = sessionFactory.openSession()) {
        session.beginTransaction();
        GitPackEntity entity = new GitPackEntity();
        entity.setRepositoryName(repositoryName);
        entity.setPackName(packName);
        entity.setPackExtension(packExtension);
        entity.setData(bytes);
        entity.setFileSize(bytes.length);
        entity.setCreatedAt(Instant.now());
        session.persist(entity);
        session.getTransaction().commit();
      }
    }

    @Override
    public void close() {
      flush();
    }
  }

  static class ByteArrayReadableChannel implements ReadableChannel {
    private final byte[] data;
    private int position;
    private boolean open = true;

    ByteArrayReadableChannel(byte[] data) {
      this.data = data;
    }

    @Override
    public int read(ByteBuffer dst) {
      int count = Math.min(dst.remaining(), data.length - position);
      if (count == 0) {
        return -1;
      }
      dst.put(data, position, count);
      position += count;
      return count;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public void position(long newPosition) {
      position = (int) newPosition;
    }

    @Override
    public long size() {
      return data.length;
    }

    @Override
    public int blockSize() {
      return 0;
    }

    @Override
    public void setReadAheadBytes(int bytes) {
      // No-op for byte arrays.
    }
  }
}
