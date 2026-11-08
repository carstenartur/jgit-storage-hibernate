#!/usr/bin/env python3
"""Behavior tests for the native follow-up evidence gate, using complete small raw fixtures."""
import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("publication", Path(__file__).with_name("consumer-publication-evidence.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def data():
    raw, native = [], []
    for workers in (1, 4):
        raw.append({"benchmark": "example.taxonomyPublishDelta", "params": {"coordinate": "1000", "parallelism": str(workers)},
                    "forks": 1, "warmupIterations": 1, "measurementIterations": 3,
                    "primaryMetric": {"rawData": [[1.0, 2.0, 3.0]]},
                    "secondaryMetrics": {key: {"rawData": [[value] * 3]} for key, value in {
                        "workspacesPublished": 4, "commitsIndexed": 4, "publicationWorkers": workers,
                        "preparedStatements": 20, "transactions": 12, "jdbcBatches": 4,
                        "gc.alloc.rate.norm": 1000, "gc.count": 0}.items()}})
        for measurement in (1, 2, 3):
            publication = measurement + 1
            native.append({"coordinate": {"operation": "taxonomyPublishDelta", "coordinate": "1000",
                            "parallelism": str(workers), "publication": str(publication), "measurement": str(measurement)},
                           "enabled": True, "backend": "postgresql",
                           "counters": {"postgresql.wal.insert_lsn_bytes": 1000},
                           "gauges": {"consumer.git_commits": 1000 + 4 * publication,
                                      "consumer.sql_commits": 1000 + 4 * publication,
                                      "consumer.lucene_commits": 1000 + 4 * publication},
                           "metadata": {"consumer.reopened_verified": "true", "consumer.membership_sha256": str(measurement) * 64,
                                        "consumer.heads": ",".join(str(index) * 40 for index in range(1, 5)),
                                        "consumer.postgresql.fsync": "on", "consumer.postgresql.synchronous_commit": "on",
                                        "consumer.postgresql.full_page_writes": "on"}})
    return raw, native


class PublicationEvidenceTest(unittest.TestCase):
    def test_complete_native_coordinates_pass(self):
        raw, native = data()
        self.assertEqual(6, len(MODULE.verify_shape("taxonomy", raw, native)))

    def test_missing_or_duplicate_native_coordinate_fails(self):
        raw, native = data()
        for changed in (native[:-1], native[:-1] + [copy.deepcopy(native[0])]):
            with self.subTest(changed=changed[-1]["coordinate"]):
                with self.assertRaises(ValueError):
                    MODULE.verify_shape("taxonomy", raw, changed)

    def test_cumulative_counter_cannot_hide_one_incorrect_iteration(self):
        raw, native = data()
        raw[0]["secondaryMetrics"]["commitsIndexed"]["rawData"] = [[3, 4, 5]]
        with self.assertRaises(ValueError):
            MODULE.verify_shape("taxonomy", raw, native)

    def test_worker_variants_must_publish_identical_git_membership(self):
        raw, native = data()
        native[-1]["metadata"]["consumer.membership_sha256"] = "a" * 64
        with self.assertRaises(ValueError):
            MODULE.verify_shape("taxonomy", raw, native)

    def test_no_wal_or_restart_proof_cannot_pass(self):
        for field, value in (("wal", 0), ("reopen", "false")):
            raw, native = data()
            if field == "wal": native[0]["counters"]["postgresql.wal.insert_lsn_bytes"] = value
            else: native[0]["metadata"]["consumer.reopened_verified"] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                MODULE.verify_shape("taxonomy", raw, native)

    def test_disabled_or_missing_durability_fails_even_with_positive_wal(self):
        for key in ("fsync", "synchronous_commit", "full_page_writes"):
            for value in ("off", None):
                raw, native = data()
                field = "consumer.postgresql." + key
                if value is None: native[0]["metadata"].pop(field)
                else: native[0]["metadata"][field] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    MODULE.verify_shape("taxonomy", raw, native)

    def test_privacy_scans_native_log_and_both_junit_report_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = [root / "raw" / "benchmark.log", root / "main-reports" / "TEST-fixture.xml",
                     root / "release-reports" / "TEST-fixture.xml"]
            for path in files:
                path.parent.mkdir(parents=True)
                path.write_text("safe diagnostic\n")
            paths = [path.parent for path in files]
            self.assertEqual(3, MODULE.privacy(paths))
            for path in files:
                for token in ("jdbc:postgresql://private-host/database", "fixture-only"):
                    path.write_text(token)
                    with self.subTest(path=path.name, token=token), self.assertRaises(ValueError):
                        MODULE.privacy(paths)
                    path.write_text("safe diagnostic\n")

    def test_privacy_rejects_unscannable_symlink_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = root / "raw"
            raw.mkdir()
            outside = root / "external"
            outside.mkdir()
            (outside / "secret.log").write_text("fixture-only")
            (raw / "unscanned").symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                MODULE.privacy([raw])


if __name__ == "__main__":
    unittest.main()
