#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {relative}, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def write_new(relative: str, content: str) -> None:
    path = ROOT / relative
    if path.exists():
        raise SystemExit(f"Refusing to replace existing file: {relative}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


write_new(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/transaction/PackPublicationSelectionMetrics.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/**
 * Monotone diagnostics for direct and pre-persisted logical-pack publication selections.
 *
 * <p>A selection is recorded before the selected database path starts, so failed attempts remain
 * visible. Staged payload bytes are the sum of locally completed pack extensions considered by the
 * selector; they are not Git object bytes and may include PACK, IDX, Reftable and auxiliary data.
 * Metrics are zero when storage diagnostics are disabled.
 *
 * @param directSelections logical packs selected for the single repository-locked transaction path
 * @param prePersistedSelections logical packs selected for lock-free payload persistence followed by
 *     short atomic publication
 * @param directStagedPayloadBytes staged bytes assigned to the direct path
 * @param prePersistedStagedPayloadBytes staged bytes assigned to the pre-persisted path
 */
public record PackPublicationSelectionMetrics(
    long directSelections,
    long prePersistedSelections,
    long directStagedPayloadBytes,
    long prePersistedStagedPayloadBytes) {

  /** Empty diagnostics snapshot. */
  public static final PackPublicationSelectionMetrics ZERO =
      new PackPublicationSelectionMetrics(0, 0, 0, 0);

  /** Validate the immutable non-negative counters. */
  public PackPublicationSelectionMetrics {
    requireNonNegative(directSelections, "directSelections");
    requireNonNegative(prePersistedSelections, "prePersistedSelections");
    requireNonNegative(directStagedPayloadBytes, "directStagedPayloadBytes");
    requireNonNegative(prePersistedStagedPayloadBytes, "prePersistedStagedPayloadBytes");
  }

  /** @return total logical-pack selections */
  public long totalSelections() {
    return Math.addExact(directSelections, prePersistedSelections);
  }

  /** @return total locally staged bytes considered by the selector */
  public long totalStagedPayloadBytes() {
    return Math.addExact(directStagedPayloadBytes, prePersistedStagedPayloadBytes);
  }

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public PackPublicationSelectionMetrics minus(PackPublicationSelectionMetrics earlier) {
    return new PackPublicationSelectionMetrics(
        difference(directSelections, earlier.directSelections, "directSelections"),
        difference(
            prePersistedSelections, earlier.prePersistedSelections, "prePersistedSelections"),
        difference(
            directStagedPayloadBytes,
            earlier.directStagedPayloadBytes,
            "directStagedPayloadBytes"),
        difference(
            prePersistedStagedPayloadBytes,
            earlier.prePersistedStagedPayloadBytes,
            "prePersistedStagedPayloadBytes"));
  }

  private static void requireNonNegative(long value, String counter) {
    if (value < 0) {
      throw new IllegalArgumentException(counter + " must not be negative");
    }
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
''',
)

write_new(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/transaction/StagingSpillMetrics.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/**
 * Monotone diagnostics for memory-first pack-extension staging spills.
 *
 * <p>A spill is counted only when bytes already retained in memory are copied to a temporary file.
 * An extension whose first write is already too large starts file-backed with a zero-byte prefix and
 * is therefore not a memory-to-file spill. Metrics are zero when storage diagnostics are disabled.
 *
 * @param memoryToFileSpills completed transitions with a non-empty in-memory prefix
 * @param spilledPrefixBytes bytes copied from memory into the new staging file during those spills
 */
public record StagingSpillMetrics(long memoryToFileSpills, long spilledPrefixBytes) {

  /** Empty diagnostics snapshot. */
  public static final StagingSpillMetrics ZERO = new StagingSpillMetrics(0, 0);

  /** Validate the immutable non-negative counters. */
  public StagingSpillMetrics {
    if (memoryToFileSpills < 0) {
      throw new IllegalArgumentException("memoryToFileSpills must not be negative");
    }
    if (spilledPrefixBytes < 0) {
      throw new IllegalArgumentException("spilledPrefixBytes must not be negative");
    }
  }

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public StagingSpillMetrics minus(StagingSpillMetrics earlier) {
    return new StagingSpillMetrics(
        difference(memoryToFileSpills, earlier.memoryToFileSpills, "memoryToFileSpills"),
        difference(spilledPrefixBytes, earlier.spilledPrefixBytes, "spilledPrefixBytes"));
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
''',
)

write_new(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/PackPublicationSelectionCounters.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import java.util.concurrent.atomic.LongAdder;

/** Repository-instance counters for the adaptive logical-pack publication selector. */
final class PackPublicationSelectionCounters {

  private static final PackPublicationSelectionCounters DISABLED =
      new PackPublicationSelectionCounters(false);

  private final LongAdder directSelections;
  private final LongAdder prePersistedSelections;
  private final LongAdder directStagedPayloadBytes;
  private final LongAdder prePersistedStagedPayloadBytes;

  private PackPublicationSelectionCounters(boolean enabled) {
    directSelections = enabled ? new LongAdder() : null;
    prePersistedSelections = enabled ? new LongAdder() : null;
    directStagedPayloadBytes = enabled ? new LongAdder() : null;
    prePersistedStagedPayloadBytes = enabled ? new LongAdder() : null;
  }

  static PackPublicationSelectionCounters from(StorageByteCounters storageByteCounters) {
    return storageByteCounters.enabled()
        ? new PackPublicationSelectionCounters(true)
        : DISABLED;
  }

  void record(boolean prePersisted, long stagedPayloadBytes) {
    if (directSelections == null) {
      return;
    }
    if (stagedPayloadBytes < 0) {
      throw new IllegalArgumentException("stagedPayloadBytes must not be negative");
    }
    if (prePersisted) {
      prePersistedSelections.increment();
      prePersistedStagedPayloadBytes.add(stagedPayloadBytes);
    } else {
      directSelections.increment();
      directStagedPayloadBytes.add(stagedPayloadBytes);
    }
  }

  PackPublicationSelectionMetrics snapshot() {
    if (directSelections == null) {
      return PackPublicationSelectionMetrics.ZERO;
    }
    return new PackPublicationSelectionMetrics(
        directSelections.sum(),
        prePersistedSelections.sum(),
        directStagedPayloadBytes.sum(),
        prePersistedStagedPayloadBytes.sum());
  }
}
''',
)

replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StorageByteCounters.java",
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;\n",
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StorageByteCounters.java",
    "  private final LongAdder readAheadOverfetchBytes;\n",
    "  private final LongAdder readAheadOverfetchBytes;\n"
    "  private final LongAdder memoryToFileSpills;\n"
    "  private final LongAdder spilledPrefixBytes;\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StorageByteCounters.java",
    "    readAheadOverfetchBytes = enabled ? new LongAdder() : null;\n",
    "    readAheadOverfetchBytes = enabled ? new LongAdder() : null;\n"
    "    memoryToFileSpills = enabled ? new LongAdder() : null;\n"
    "    spilledPrefixBytes = enabled ? new LongAdder() : null;\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StorageByteCounters.java",
    "  void recordReadAheadOverfetchBytes(long bytes) {\n    add(readAheadOverfetchBytes, bytes);\n  }\n\n  StorageByteMetrics snapshot() {\n",
    "  void recordReadAheadOverfetchBytes(long bytes) {\n    add(readAheadOverfetchBytes, bytes);\n  }\n\n"
    "  void recordMemoryToFileSpill(long prefixBytes) {\n"
    "    if (memoryToFileSpills == null || prefixBytes == 0) {\n"
    "      return;\n"
    "    }\n"
    "    if (prefixBytes < 0) {\n"
    "      throw new IllegalArgumentException(\"prefixBytes must not be negative\");\n"
    "    }\n"
    "    memoryToFileSpills.increment();\n"
    "    spilledPrefixBytes.add(prefixBytes);\n"
    "  }\n\n"
    "  StorageByteMetrics snapshot() {\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StorageByteCounters.java",
    "  private static void add(LongAdder counter, long bytes) {\n",
    "  StagingSpillMetrics stagingSpillSnapshot() {\n"
    "    if (!enabled()) {\n"
    "      return StagingSpillMetrics.ZERO;\n"
    "    }\n"
    "    return new StagingSpillMetrics(memoryToFileSpills.sum(), spilledPrefixBytes.sum());\n"
    "  }\n\n"
    "  private static void add(LongAdder counter, long bytes) {\n",
)

replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/PackExtensionStagingBuffer.java",
    "      fileChannel = candidateChannel;\n      temporaryFile = candidate;\n",
    "      storageByteCounters.recordMemoryToFileSpill(fileSize);\n"
    "      fileChannel = candidateChannel;\n"
    "      temporaryFile = candidate;\n",
)

replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagedPackExtensionStore.java",
    "  private final StorageByteCounters storageByteCounters;\n",
    "  private final StorageByteCounters storageByteCounters;\n"
    "  private final PackPublicationSelectionCounters publicationSelectionCounters;\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagedPackExtensionStore.java",
    "  StagedPackExtensionStore(\n      String repositoryName,\n      HibernateTransactionContext transactionContext,\n      PrePublicationHook prePublicationHook,\n      long minimumPrePersistedPayloadBytes,\n      StorageByteCounters storageByteCounters) {\n    this.repositoryName = Objects.requireNonNull(repositoryName, \"repositoryName\");\n",
    "  StagedPackExtensionStore(\n"
    "      String repositoryName,\n"
    "      HibernateTransactionContext transactionContext,\n"
    "      PrePublicationHook prePublicationHook,\n"
    "      long minimumPrePersistedPayloadBytes,\n"
    "      StorageByteCounters storageByteCounters) {\n"
    "    this(\n"
    "        repositoryName,\n"
    "        transactionContext,\n"
    "        prePublicationHook,\n"
    "        minimumPrePersistedPayloadBytes,\n"
    "        storageByteCounters,\n"
    "        PackPublicationSelectionCounters.from(storageByteCounters));\n"
    "  }\n\n"
    "  private StagedPackExtensionStore(\n"
    "      String repositoryName,\n"
    "      HibernateTransactionContext transactionContext,\n"
    "      PrePublicationHook prePublicationHook,\n"
    "      long minimumPrePersistedPayloadBytes,\n"
    "      StorageByteCounters storageByteCounters,\n"
    "      PackPublicationSelectionCounters publicationSelectionCounters) {\n"
    "    this.repositoryName = Objects.requireNonNull(repositoryName, \"repositoryName\");\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagedPackExtensionStore.java",
    "    this.storageByteCounters = Objects.requireNonNull(storageByteCounters, \"storageByteCounters\");\n  }\n\n  DfsOutputStream open",
    "    this.storageByteCounters = Objects.requireNonNull(storageByteCounters, \"storageByteCounters\");\n"
    "    this.publicationSelectionCounters =\n"
    "        Objects.requireNonNull(publicationSelectionCounters, \"publicationSelectionCounters\");\n"
    "  }\n\n"
    "  DfsOutputStream open",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagedPackExtensionStore.java",
    "    CommitResult commitResult =\n        shouldPrePersist(publications, replaces)\n            ? commitPrePersisted(publications)\n            : commitDirect(publications, replaces);\n",
    "    boolean prePersisted = shouldPrePersist(publications, replaces);\n"
    "    publicationSelectionCounters.record(prePersisted, stagedPayloadBytes(publications));\n"
    "    CommitResult commitResult =\n"
    "        prePersisted\n"
    "            ? commitPrePersisted(publications)\n"
    "            : commitDirect(publications, replaces);\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagedPackExtensionStore.java",
    "  io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics\n      storageByteMetricsSnapshot() {\n    return storageByteCounters.snapshot();\n  }\n\n  private void register",
    "  io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics\n"
    "      storageByteMetricsSnapshot() {\n"
    "    return storageByteCounters.snapshot();\n"
    "  }\n\n"
    "  io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics\n"
    "      stagingSpillMetricsSnapshot() {\n"
    "    return storageByteCounters.stagingSpillSnapshot();\n"
    "  }\n\n"
    "  io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics\n"
    "      publicationSelectionMetricsSnapshot() {\n"
    "    return publicationSelectionCounters.snapshot();\n"
    "  }\n\n"
    "  long minimumPrePersistedPayloadBytes() {\n"
    "    return minimumPrePersistedPayloadBytes;\n"
    "  }\n\n"
    "  private void register",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagedPackExtensionStore.java",
    "  private static long committedPayloadBytes(List<CommittedExtension> extensions) {\n",
    "  private static long stagedPayloadBytes(List<Publication> publications) {\n"
    "    long total = 0;\n"
    "    for (Publication publication : publications) {\n"
    "      for (ExpectedExtension expected : publication.extensions()) {\n"
    "        if (expected.staged() != null) {\n"
    "          total = saturatingAdd(total, expected.staged().fileSize());\n"
    "        }\n"
    "      }\n"
    "    }\n"
    "    return total;\n"
    "  }\n\n"
    "  private static long committedPayloadBytes(List<CommittedExtension> extensions) {\n",
)

replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.java",
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;\nimport io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;\n",
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.java",
    "  public StorageByteMetrics storageByteMetricsSnapshot() {\n    return storageByteCounters.snapshot();\n  }\n\n  private CatalogState beginCatalogMutation",
    "  public StorageByteMetrics storageByteMetricsSnapshot() {\n"
    "    return storageByteCounters.snapshot();\n"
    "  }\n\n"
    "  /** @return current memory-to-file staging spill diagnostics */\n"
    "  public StagingSpillMetrics stagingSpillMetricsSnapshot() {\n"
    "    return stagedExtensions.stagingSpillMetricsSnapshot();\n"
    "  }\n\n"
    "  /** @return current direct-versus-pre-persisted publication selection diagnostics */\n"
    "  public PackPublicationSelectionMetrics publicationSelectionMetricsSnapshot() {\n"
    "    return stagedExtensions.publicationSelectionMetricsSnapshot();\n"
    "  }\n\n"
    "  /** @return deterministic staged-payload threshold used by the adaptive selector */\n"
    "  public long minimumPrePersistedPayloadBytes() {\n"
    "    return stagedExtensions.minimumPrePersistedPayloadBytes();\n"
    "  }\n\n"
    "  private CatalogState beginCatalogMutation",
)

replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/repository/HibernateRepository.java",
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;\nimport io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;\n",
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;\n"
    "import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;\n",
)
replace_once(
    "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/repository/HibernateRepository.java",
    "  public StorageByteMetrics getStorageByteMetrics() {\n    return objectDatabase.storageByteMetricsSnapshot();\n  }\n\n  /** Execute repository storage work in one shared transaction. */\n",
    "  public StorageByteMetrics getStorageByteMetrics() {\n"
    "    return objectDatabase.storageByteMetricsSnapshot();\n"
    "  }\n\n"
    "  /**\n"
    "   * Return monotone memory-to-file staging spill counters.\n"
    "   *\n"
    "   * @return current staging spill metrics, or zero counters when diagnostics are disabled\n"
    "   */\n"
    "  public StagingSpillMetrics getStagingSpillMetrics() {\n"
    "    return objectDatabase.stagingSpillMetricsSnapshot();\n"
    "  }\n\n"
    "  /**\n"
    "   * Return monotone direct-versus-pre-persisted pack publication selections.\n"
    "   *\n"
    "   * @return current selection metrics, or zero counters when diagnostics are disabled\n"
    "   */\n"
    "  public PackPublicationSelectionMetrics getPackPublicationSelectionMetrics() {\n"
    "    return objectDatabase.publicationSelectionMetricsSnapshot();\n"
    "  }\n\n"
    "  /**\n"
    "   * Return the deterministic staged-payload threshold for two-phase publication.\n"
    "   *\n"
    "   * @return minimum staged logical-pack bytes selected for pre-persistence\n"
    "   */\n"
    "  public long getMinimumPrePersistedPackPayloadBytes() {\n"
    "    return objectDatabase.minimumPrePersistedPayloadBytes();\n"
    "  }\n\n"
    "  /** Execute repository storage work in one shared transaction. */\n",
)

write_new(
    "jgit-storage-hibernate-core/src/test/java/io/github/carstenartur/jgit/storage/hibernate/repository/PackPublicationSelectionMetricsH2Test.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;

class PackPublicationSelectionMetricsH2Test {

  @Test
  void exposesDirectAndPrePersistedSelectionsWithoutChangingTheFixedDefault() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "selection-metrics")) {
      repository.create(true);
      assertEquals(1024L * 1024L, repository.getMinimumPrePersistedPackPayloadBytes());
      PackPublicationSelectionMetrics before = repository.getPackPublicationSelectionMetrics();

      insert(repository, 384 * 1024 + 17, 17);
      PackPublicationSelectionMetrics direct =
          repository.getPackPublicationSelectionMetrics().minus(before);
      assertEquals(1, direct.directSelections());
      assertEquals(0, direct.prePersistedSelections());
      assertTrue(direct.directStagedPayloadBytes() > 256 * 1024);

      PackPublicationSelectionMetrics beforeLarge =
          repository.getPackPublicationSelectionMetrics();
      insert(repository, 2 * 1024 * 1024 + 31, 29);
      PackPublicationSelectionMetrics prePersisted =
          repository.getPackPublicationSelectionMetrics().minus(beforeLarge);
      assertEquals(0, prePersisted.directSelections());
      assertEquals(1, prePersisted.prePersistedSelections());
      assertTrue(prePersisted.prePersistedStagedPayloadBytes() >= 1024 * 1024);
    }
  }

  private static void insert(HibernateRepository repository, int length, int seed)
      throws Exception {
    byte[] payload = new byte[length];
    new Random(seed).nextBytes(payload);
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      inserter.insert(Constants.OBJ_BLOB, payload);
      inserter.flush();
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:selection-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
''',
)

write_new(
    "jgit-storage-hibernate-core/src/test/java/io/github/carstenartur/jgit/storage/hibernate/objects/StagingSpillMetricsH2Test.java",
    '''/*
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
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StagingSpillMetricsH2Test {

  @Test
  void countsOnlyTheMemoryPrefixCopiedDuringARealSpill() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      StorageByteCounters counters = StorageByteCounters.from(provider.getSessionFactory());
      int prefixBytes = 128 * 1024;
      byte[] prefix = new byte[prefixBytes];
      byte[] suffix = new byte[PackExtensionStagingBuffer.MAX_MEMORY_BYTES];
      StagingSpillMetrics before = counters.stagingSpillSnapshot();

      try (PackExtensionStagingBuffer buffer =
          new PackExtensionStagingBuffer(counters, (payload, size, createdAt) -> payload.discard())) {
        buffer.write(prefix, 0, prefix.length);
        buffer.write(suffix, 0, suffix.length);
      }

      StagingSpillMetrics delta = counters.stagingSpillSnapshot().minus(before);
      assertEquals(1, delta.memoryToFileSpills());
      assertEquals(prefixBytes, delta.spilledPrefixBytes());
    }
  }

  @Test
  void anOversizedFirstWriteStartsFileBackedWithoutAFalseSpill() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      StorageByteCounters counters = StorageByteCounters.from(provider.getSessionFactory());
      byte[] payload = new byte[PackExtensionStagingBuffer.MAX_MEMORY_BYTES + 1];

      try (PackExtensionStagingBuffer buffer =
          new PackExtensionStagingBuffer(counters, (staged, size, createdAt) -> staged.discard())) {
        buffer.write(payload, 0, payload.length);
      }

      assertEquals(StagingSpillMetrics.ZERO, counters.stagingSpillSnapshot());
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:spill-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
''',
)

print("Core performance diagnostics staged successfully")
