#!/usr/bin/env python3
"""Apply the bounded issue #188 capacity sparse-evidence repair."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one replacement in {path}, found {count}")
    target.write_text(text.replace(old, new), encoding="utf-8")


replace_once(
    ".github/scripts/convert-jmh-pack-storage-layout.py",
    '''            and (
                summary["worstSparseImprovementPercent"] is None
                or summary["worstSparseImprovementPercent"] >= -5.0
            )
''',
    '''            and summary["worstSparseImprovementPercent"] is not None
            and summary["worstSparseImprovementPercent"] >= -5.0
''',
)

replace_once(
    ".github/scripts/test_convert_jmh_pack_storage_layout.py",
    '''        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    '''        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_missing_sparse_evidence_cannot_promote_a_candidate(self) -> None:
        results = [
            result
            for result in self.matrix(
                ["postgresql", "sqlserver"],
                sparse_candidate=9.7,
            )
            if result["params"]["operation"] not in CONVERTER.SPARSE_OPERATIONS
        ]
        report = CONVERTER.convert(results)
        candidate = next(
            item
            for item in report["layoutCandidates"]
            if item["chunkKiB"] == 2048 and item["inlineKiB"] == 256
        )
        self.assertFalse(candidate["eligible"])
        self.assertTrue(
            all(
                evidence["worstSparseImprovementPercent"] is None
                for evidence in candidate["backendEvidence"]
            )
        )
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
)

replace_once(
    "jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/PackStorageLayoutBenchmarkTest.java",
    '''  @Test
  void fullAndCapacityProfilesDoNotRepeatBenchmarkCoordinates() {
    assertUniqueCoordinates("full");
    assertUniqueCoordinates("capacity");
  }

  private static void assertUniqueCoordinates(String profile) {
''',
    '''  @Test
  void fullAndCapacityProfilesDoNotRepeatBenchmarkCoordinates() {
    assertUniqueCoordinates("full");
    assertUniqueCoordinates("capacity");
  }

  @Test
  void capacityProfileIncludesSparseReadsForEveryChunkSize() {
    Set<String> coordinates = new HashSet<>();
    for (Scenario scenario : scenarios("capacity")) {
      for (String operation : scenario.operations()) {
        if (!PackStorageLayoutBenchmark.SHORT_READ.equals(operation)
            && !PackStorageLayoutBenchmark.RANDOM_READ.equals(operation)) {
          continue;
        }
        for (String payloadKiB : scenario.payloadKiB()) {
          if (!"524288".equals(payloadKiB)) {
            continue;
          }
          for (String readAheadKiB : scenario.readAheadKiB()) {
            if (!"1024".equals(readAheadKiB)) {
              continue;
            }
            for (String chunkKiB : scenario.chunkKiB()) {
              coordinates.add(operation + ":" + chunkKiB);
            }
          }
        }
      }
    }

    for (String chunkKiB : chunkSizes()) {
      assertTrue(
          coordinates.contains(PackStorageLayoutBenchmark.SHORT_READ + ":" + chunkKiB),
          () -> "Capacity profile is missing short-read evidence for chunk " + chunkKiB);
      assertTrue(
          coordinates.contains(PackStorageLayoutBenchmark.RANDOM_READ + ":" + chunkKiB),
          () -> "Capacity profile is missing random-read evidence for chunk " + chunkKiB);
    }
  }

  private static void assertUniqueCoordinates(String profile) {
''',
)

replace_once(
    "jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/PackStorageLayoutBenchmarkTest.java",
    '''              new Scenario(
                  new String[] {PackStorageLayoutBenchmark.SEQUENTIAL_READ},
                  new String[] {"524288"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  new String[] {"1024", "4096", "16384"}));
''',
    '''              new Scenario(
                  new String[] {PackStorageLayoutBenchmark.SEQUENTIAL_READ},
                  new String[] {"524288"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  new String[] {"1024", "4096", "16384"}),
              new Scenario(
                  new String[] {
                    PackStorageLayoutBenchmark.SHORT_READ,
                    PackStorageLayoutBenchmark.RANDOM_READ
                  },
                  new String[] {"524288"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  new String[] {"1024"}));
''',
)
