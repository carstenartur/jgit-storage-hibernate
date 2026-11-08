/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.internal.storage.io.BlockSource;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.reftable.RefCursor;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.internal.storage.reftable.ReftableReader;
import org.eclipse.jgit.internal.storage.reftable.ReftableWriter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class ReftableOutputAlignmentTest {
  @Test
  void defaultRefTableUsesJGitFourKiBBlocksAndPreservesEveryRef() throws Exception {
    writeAndReadReftable(new ReftableConfig(), 4096, false);
  }

  @Test
  void explicitRefBlockSizeSurvivesTheStorageOutput() throws Exception {
    ReftableConfig config = new ReftableConfig();
    config.setRefBlockSize(8192);
    writeAndReadReftable(config, 8192, false);
  }

  @Test
  void unalignedRefLayoutSurvivesTheStorageOutput() throws Exception {
    ReftableConfig config = new ReftableConfig();
    config.setRefBlockSize(8192);
    config.setAlignBlocks(false);
    writeAndReadReftable(config, 8192, true);
  }

  @Test
  void packAndOtherExtensionOutputKeepsChunkAlignmentAndPositionalReads() throws Exception {
    try (InMemoryRepository owner = new InMemoryRepository(new DfsRepositoryDescription("pack-alignment"))) {
      ReadAheadHibernateObjDatabase database = database(owner);
      for (PackExt extension : PackExt.values()) {
        if (extension == PackExt.REFTABLE) continue;
        DfsPackDescription description = description(owner, extension);
        try {
          try (DfsOutputStream stream = database.writeFile(description, extension)) {
            assertEquals(1024 * 1024, stream.blockSize(), extension.toString());
            stream.write(new byte[] {7, 11, 19});
            stream.flush();
            ByteBuffer bytes = ByteBuffer.allocate(2);
            assertEquals(2, stream.read(1, bytes));
            assertArrayEquals(new byte[] {11, 19}, bytes.array());
          }
        } finally {
          database.rollbackPack(List.of(description));
        }
        assertEquals(0, database.stagedExtensionCount());
      }
    }
  }

  private static void writeAndReadReftable(ReftableConfig requested, int expectedBlockSize,
      boolean unaligned) throws Exception {
    try (InMemoryRepository owner = new InMemoryRepository(new DfsRepositoryDescription("ref-alignment"))) {
      ReadAheadHibernateObjDatabase database = database(owner);
      DfsPackDescription description = description(owner, PackExt.REFTABLE);
      try {
        try (DfsOutputStream stream = database.writeFile(description, PackExt.REFTABLE)) {
          // Exercise JGit's actual adapter, used for both publication and compaction.
          Method configure = DfsPackCompactor.class.getDeclaredMethod("configureReftable",
              ReftableConfig.class, DfsOutputStream.class);
          configure.setAccessible(true);
          ReftableConfig effective = (ReftableConfig) configure.invoke(null, requested, stream);
          ReftableWriter writer = new ReftableWriter(effective, stream)
              .setMinUpdateIndex(1).setMaxUpdateIndex(1).begin();
          for (int index = 1; index <= 512; index++) {
            writer.writeRef(new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, refName(index), objectId(index)));
          }
          writer.finish();
          ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(writer.getStats().totalBytes()));
          assertEquals(bytes.capacity(), stream.read(0, bytes));
          try (ReftableReader reader = new ReftableReader(BlockSource.from(bytes.array()));
              RefCursor refs = reader.allRefs()) {
            // JGit encodes zero in the file header for an unaligned table.
            assertEquals(unaligned ? 0 : expectedBlockSize, reader.blockSize(),
                "Stored reftable header must honor its configuration");
            assertEquals(expectedBlockSize, writer.getStats().refBlockSize());
            for (int index = 1; index <= 512; index++) {
              assertTrue(refs.next());
              assertEquals(refName(index), refs.getRef().getName());
              assertEquals(objectId(index), refs.getRef().getObjectId());
            }
            assertFalse(refs.next());
          }
          if (unaligned) assertEquals(0, writer.getStats().paddingBytes());
        }
      } finally {
        database.rollbackPack(List.of(description));
      }
      assertEquals(0, database.stagedExtensionCount());
    }
  }

  private static ReadAheadHibernateObjDatabase database(InMemoryRepository owner) {
    // writeFile stages bytes locally. This strict properties-only factory makes
    // any unexpected database access fail rather than hiding it behind a mock.
    SessionFactory factory = (SessionFactory) Proxy.newProxyInstance(
        SessionFactory.class.getClassLoader(), new Class<?>[] {SessionFactory.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getProperties")) return Map.of();
          throw new AssertionError("Unexpected database access while staging: " + method.getName());
        });
    return new ReadAheadHibernateObjDatabase(owner, new DfsReaderOptions(), factory,
        owner.getDescription().getRepositoryName(), new HibernateTransactionContext(factory));
  }

  private static DfsPackDescription description(InMemoryRepository owner, PackExt extension) {
    DfsPackDescription description = new DfsPackDescription(owner.getDescription(),
        "alignment-" + extension.getExtension(), PackSource.INSERT);
    description.addFileExt(extension);
    return description;
  }

  private static String refName(int index) {
    return "refs/heads/configured-block-%04d".formatted(index);
  }

  private static ObjectId objectId(int index) {
    return ObjectId.fromString("%040x".formatted(index));
  }
}
