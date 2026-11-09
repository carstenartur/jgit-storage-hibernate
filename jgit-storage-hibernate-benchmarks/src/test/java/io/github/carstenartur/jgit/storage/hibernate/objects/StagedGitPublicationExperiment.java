/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.objects.PackExtensionStagingBuffer.StagedPayloadReader;
import io.github.carstenartur.jgit.storage.hibernate.objects.StagedChunkPipelineExperiment.Mode;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.SessionFactory;

/**
 * Full Git publication using the production policy, replacing only the benchmark's chunk transfer.
 */
public final class StagedGitPublicationExperiment {
  public static final String REF = "refs/heads/staging-pipeline";
  private final Mode mode;
  private final long readBytesPerSecond;
  private final AfterTransfer afterTransfer;
  private int retainedWindows;

  public StagedGitPublicationExperiment(Mode mode, long readBytesPerSecond) {
    this(mode, readBytesPerSecond, () -> {});
  }

  StagedGitPublicationExperiment(Mode mode, long readBytesPerSecond, AfterTransfer afterTransfer) {
    this.mode = mode;
    this.readBytesPerSecond = readBytesPerSecond;
    this.afterTransfer = afterTransfer;
  }

  public ReadAheadHibernateObjDatabase database(
      DfsRepository repository,
      DfsReaderOptions options,
      SessionFactory factory,
      String name,
      HibernateTransactionContext context) {
    return new ReadAheadHibernateObjDatabase(
        repository,
        options,
        factory,
        name,
        context,
        (reader, writer, packId, size) -> {
          var source = StagedChunkPipelineExperiment.pacedSource(reader::read, readBytesPerSecond);
          if (mode == Mode.SYNCHRONOUS) {
            // Keep the real production allocation/batching loop as the reference.
            StagedPackExtensionStore.transferSynchronously(
                new StagedPayloadReader() {
                  @Override
                  public int read(long position, ByteBuffer target) throws IOException {
                    return source.read(position, target);
                  }

                  @Override
                  public void close() {
                    /* The store owns the underlying reader. */
                  }
                },
                writer,
                packId,
                size);
            retainedWindows = Math.max(retainedWindows, 1);
          } else {
            var transfer =
                StagedChunkPipelineExperiment.transfer(
                    source, writer::insert, packId, size, writer.batchSize(), mode);
            retainedWindows = Math.max(retainedWindows, transfer.retainedWindows());
          }
          afterTransfer.run();
        });
  }

  /** Starts before streaming/compression and stops only after normal ref publication returns. */
  public Publication publish(HibernateRepository repository, Path payload) throws IOException {
    long started = System.nanoTime();
    ObjectId blob;
    ObjectId commitId;
    try (var input = Files.newInputStream(payload);
        var inserter = repository.newObjectInserter()) {
      blob = inserter.insert(Constants.OBJ_BLOB, Files.size(payload), input);
      var tree = new TreeFormatter();
      tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
      var commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      var identity =
          new PersonIdent(
              "Pipeline benchmark", "pipeline@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
      commit.setAuthor(identity);
      commit.setCommitter(identity);
      commit.setMessage("bounded staging publication\n");
      commitId = inserter.insert(commit);
      inserter.flush();
    }
    var update = repository.updateRef(REF);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(commitId);
    update.disableRefLog();
    if (update.update() != RefUpdate.Result.NEW) {
      throw new IOException("Git publication did not create its ref");
    }
    return new Publication(commitId, blob, System.nanoTime() - started, retainedWindows);
  }

  public record Publication(
      ObjectId commit, ObjectId blob, long elapsedNanos, int retainedWindows) {}

  @FunctionalInterface
  interface AfterTransfer {
    void run() throws IOException;
  }
}
