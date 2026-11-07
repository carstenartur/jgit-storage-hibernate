#!/usr/bin/env python3
"""Exercise evidence loss/corruption cases with independently specified counts."""

import csv
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "consumer_evidence", Path(__file__).with_name("verify-consumer-benchmark-evidence.py")
)
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)

COUNTERS = (
    "payloadBytes", "preparedStatements", "transactions", "databaseBytesRead",
    "databaseBytesWritten", "jdbcBatches", "retainedAnalysisBytes", "javaFilesAnalyzed",
    "resultCount",
)
KEYS = (
    "suite", "librarySha", "shape", "operation", "coordinate", "writer", "pressure",
    "chunkBatch", "measurement",
)


class ConsumerEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = b"class Fixture {}\n"
        (self.root / "fixture.java.gz").write_bytes(gzip.compress(self.source, mtime=0))
        self.record = {
            "benchmark": "example.ConsumerShapeBenchmark.audioPublish",
            "params": {"coordinate": "32", "writer": "auto", "pressure": "idle", "chunkBatch": "16"},
            "primaryMetric": {"score": 1.0, "rawData": [[1.0]]},
            "secondaryMetrics": {
                "payloadBytes": {"rawData": [[32.0]]},
                "gc.alloc.rate.norm": {"rawData": [[128.0]]},
                "gc.count": {"rawData": [[0.0]]},
            },
        }
        self.manifest = {
            "schemaVersion": 1,
            "normalizedCsv": "normalized.csv",
            "harnesses": {"a" * 40: {"sourcesSha256": hashlib.sha256(self.source).hexdigest(), "sources": ["fixture.java.gz"]}},
            "series": [{
                "suite": "sample", "librarySha": "b" * 40, "harnessSha": "a" * 40,
                "raw": [{"shape": "audio", "json": "audio.json.gz", "jsonSha256": "", "log": "audio.log.gz", "logSha256": hashlib.sha256(b"raw log\n").hexdigest()}],
            }],
        }
        (self.root / "audio.log.gz").write_bytes(gzip.compress(b"raw log\n", mtime=0))
        self.row = dict.fromkeys((*KEYS, *COUNTERS), "")
        self.row.update(suite="sample", librarySha="b" * 40, shape="audio", operation="audioPublish", coordinate="32", writer="auto", pressure="idle", chunkBatch="16", measurement="1", payloadBytes="32")
        self.write_raw([self.record])
        self.write_csv()

    def write_raw(self, records):
        data = json.dumps(records).encode()
        (self.root / "audio.json.gz").write_bytes(gzip.compress(data, mtime=0))
        self.manifest["series"][0]["raw"][0]["jsonSha256"] = hashlib.sha256(data).hexdigest()
        (self.root / "manifest.json").write_text(json.dumps(self.manifest))

    def write_csv(self):
        with (self.root / "normalized.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=(*KEYS, *COUNTERS))
            writer.writeheader()
            writer.writerow(self.row)

    def test_accepts_the_original_per_iteration_observation(self):
        self.assertEqual(1, EVIDENCE.verify(self.root / "manifest.json")["observations"])

    def test_rejects_a_changed_normalized_counter(self):
        self.row["payloadBytes"] = "31"
        self.write_csv()
        with self.assertRaisesRegex(ValueError, "payloadBytes"):
            EVIDENCE.verify(self.root / "manifest.json")

    def test_rejects_missing_gc_evidence_even_if_the_file_hash_is_updated(self):
        del self.record["secondaryMetrics"]["gc.alloc.rate.norm"]
        self.write_raw([self.record])
        with self.assertRaisesRegex(ValueError, "gc.alloc.rate.norm"):
            EVIDENCE.verify(self.root / "manifest.json")

    def test_rejects_changed_raw_content(self):
        (self.root / "audio.json.gz").write_bytes(gzip.compress(b"[]", mtime=0))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            EVIDENCE.verify(self.root / "manifest.json")

    def test_rejects_lost_iterations(self):
        self.record["primaryMetric"]["rawData"] = [[1.0, 2.0]]
        self.write_raw([self.record])
        with self.assertRaisesRegex(ValueError, "iteration"):
            EVIDENCE.verify(self.root / "manifest.json")

    def test_rejects_duplicate_raw_coordinates(self):
        self.write_raw([self.record, self.record])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            EVIDENCE.verify(self.root / "manifest.json")


if __name__ == "__main__":
    unittest.main()
