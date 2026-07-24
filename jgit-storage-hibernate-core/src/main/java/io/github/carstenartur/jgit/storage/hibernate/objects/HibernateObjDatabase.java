/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
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
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.SessionFactory;

/**
 * {@link DfsObjDatabase} backed by Hibernate-managed relational database rows.
 *
 * <p>Pack extensions are first written as uncommitted rows and become visible only when JGit calls
 * {@link #commitPackImpl(Collection, Collection)}. This mirrors the DFS contract more closely than
 * immediately exposing partially written pack files.
 */
public class HibernateObjDatabase extends DfsObjDatabase {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final HibernateTransactionContext transactionContext;
  private Set<ObjectId> shallowCommits = Collections.emptySet();

  /**
   * Create an object database.
   *
   * @param repository owning repository
   * @param options DFS reader options
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   * @param transactionContext repository-scoped transaction context
   */
  public HibernateObjDatabase(
      DfsRepository repository,
      DfsReaderOptions options,
      SessionFactory sessionFactory,
      String repositoryName,
      HibernateTransactionContext transactionContext) {
    super(repository, options);
    this.sessionFactory = sessionFactory;
    this.repositoryName = repositoryName;
    this.transactionContext = transactionContext;
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    return transactionContext.execute(
        session -> {
          List<Object[]> rows =
              session
                  .createQuery(
                      "SELECT p.packName, p.packExtension FROM GitPackEntity p "
                          + "WHERE p.repositoryName = :repo AND p.committed = true",
                      Object[].class)
                  .setParameter("repo", repositoryName)
                  .getResultList();
          LinkedHashMap<String, DfsPackDescription> descriptions = new LinkedHashMap<>();
          for (Object[] row : rows) {
            String packName = (String) row[0];
            String extension = (String) row[1];
            DfsPackDescription description =
                descriptions.computeIfAbsent(
                    packName,
                    name ->
                        new DfsPackDescription(
                            getRepository().getDescription(), name, PackSource.INSERT));
            for (PackExt packExtension : PackExt.values()) {
              if (packExtension.getExtension().equals(extension)) {
                description.addFileExt(packExtension);
                break;
              }
            }
          }
          return new ArrayList<>(descriptions.values());
        });
  }

  @Override
  protected DfsPackDescription newPack(PackSource source) {
    String name = "pack-" + source.name().toLowerCase() + "-" + UUID.randomUUID();
    return new DfsPackDescription(getRepository().getDescription(), name, source);
  }

  @Override
  protected void commitPackImpl(
      Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replaces)
      throws IOException {
    transactionContext.execute(
        session -> {
          if (replaces != null) {
            for (DfsPackDescription replace : replaces) {
              session
                  .createMutationQuery(
                      "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                          + "AND p.packName = :name")
                  .setParameter("repo", repositoryName)
                  .setParameter("name", baseName(replace))
                  .executeUpdate();
            }
          }
          Instant committedAt = Instant.now();
          for (DfsPackDescription description : descriptions) {
            session
                .createMutationQuery(
                    "UPDATE GitPackEntity p SET p.committed = true, "
                        + "p.committedAt = :committedAt WHERE p.repositoryName = :repo "
                        + "AND p.packName = :name")
                .setParameter("committedAt", committedAt)
                .setParameter("repo", repositoryName)
                .setParameter("name", baseName(description))
                .executeUpdate();
          }
          return null;
        });
    clearCache();
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
    try {
      transactionContext.execute(
          session -> {
            for (DfsPackDescription description : descriptions) {
              session
                  .createMutationQuery(
                      "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                          + "AND p.packName = :name")
                  .setParameter("repo", repositoryName)
                  .setParameter("name", baseName(description))
                  .executeUpdate();
            }
            return null;
          });
    } catch (IOException | RuntimeException ignored) {
      // Rollback is best-effort and must not mask the original JGit exception.
    }
  }

  @Override
  protected ReadableChannel openFile(DfsPackDescription description, PackExt extension)
      throws FileNotFoundException, IOException {
    return transactionContext.execute(
        session -> {
          GitPackEntity entity =
              session
                  .createQuery(
                      "FROM GitPackEntity p WHERE p.repositoryName = :repo AND p.packName = :name "
                          + "AND p.packExtension = :ext AND p.committed = true",
                      GitPackEntity.class)
                  .setParameter("repo", repositoryName)
                  .setParameter("name", baseName(description))
                  .setParameter("ext", extension.getExtension())
                  .uniqueResult();
          if (entity == null) {
            throw new FileNotFoundException(description.getFileName(extension));
          }
          return new ByteArrayReadableChannel(entity.getData());
        });
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    return new HibernatePackOutputStream(
        transactionContext, repositoryName, baseName(description), extension.getExtension());
  }

  @Override
  public Set<ObjectId> getShallowCommits() throws IOException {
    return shallowCommits;
  }

  @Override
  public void setShallowCommits(Set<ObjectId> shallowCommits) {
    this.shallowCommits = shallowCommits != null ? shallowCommits : Collections.emptySet();
  }

  @Override
  public long getApproximateObjectCount() {
    // Pack-blob storage does not maintain a reliable object-level count.
    return 0L;
  }

  private static final class HibernatePackOutputStream extends DfsOutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final HibernateTransactionContext transactionContext;
    private final String repositoryName;
    private final String packName;
    private final String packExtension;
    private byte[] data;
    private boolean flushed;

    private HibernatePackOutputStream(
        HibernateTransactionContext transactionContext,
        String repositoryName,
        String packName,
        String packExtension) {
      this.transactionContext = transactionContext;
      this.repositoryName = repositoryName;
      this.packName = packName;
      this.packExtension = packExtension;
    }

    @Override
    public void write(byte[] source, int offset, int length) {
      data = null;
      buffer.write(source, offset, length);
    }

    @Override
    public int read(long position, ByteBuffer destination) {
      byte[] bytes = bytes();
      int count = Math.min(destination.remaining(), bytes.length - (int) position);
      if (count <= 0) {
        return -1;
      }
      destination.put(bytes, (int) position, count);
      return count;
    }

    @Override
    public void flush() throws IOException {
      if (flushed) {
        return;
      }
      flushed = true;
      byte[] bytes = bytes();
      transactionContext.execute(
          session -> {
            GitPackEntity entity =
                session
                    .createQuery(
                        "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                            + "AND p.packName = :name AND p.packExtension = :ext",
                        GitPackEntity.class)
                    .setParameter("repo", repositoryName)
                    .setParameter("name", packName)
                    .setParameter("ext", packExtension)
                    .uniqueResult();
            if (entity == null) {
              entity = new GitPackEntity();
              entity.setRepositoryName(repositoryName);
              entity.setPackName(packName);
              entity.setPackExtension(packExtension);
              entity.setCreatedAt(Instant.now());
            }
            entity.setData(bytes);
            entity.setFileSize(bytes.length);
            entity.setCommitted(false);
            entity.setCommittedAt(null);
            if (entity.getId() == null) {
              session.persist(entity);
            }
            return null;
          });
    }

    @Override
    public void close() throws IOException {
      flush();
    }

    private byte[] bytes() {
      if (data == null) {
        data = buffer.toByteArray();
      }
      return data;
    }
  }

  private static final class ByteArrayReadableChannel implements ReadableChannel {
    private final byte[] data;
    private int position;
    private boolean open = true;

    private ByteArrayReadableChannel(byte[] data) {
      this.data = data;
    }

    @Override
    public int read(ByteBuffer destination) {
      int count = Math.min(destination.remaining(), data.length - position);
      if (count <= 0) {
        return -1;
      }
      destination.put(data, position, count);
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
      position = Math.toIntExact(newPosition);
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
    public void setReadAheadBytes(int readAheadBytes) {
      // Byte array backed channel does not need read-ahead configuration.
    }
  }
}
