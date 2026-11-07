#!/usr/bin/env python3
"""Regression checks for the retained routing evidence, without running benchmarks."""

import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("summarize-search-routing.py")
SPEC = importlib.util.spec_from_file_location("routing_summary", SCRIPT)
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)
EVIDENCE = SCRIPT.parents[2] / "docs/evidence/search-routing-large-2026-11-07"


class RoutingEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(EVIDENCE / "raw", self.root / "raw")
        shutil.copyfile(EVIDENCE / "provenance.json", self.root / "provenance.json")

    def refresh_digest(self, artifact, relative):
        manifest_path = self.root / "provenance.json"
        manifest = json.loads(manifest_path.read_text())
        raw = (self.root / "raw" / artifact / relative).read_bytes()
        record, = (item for item in manifest["artifacts"] if item["name"] == artifact)
        record["files"][relative] = {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
        manifest_path.write_text(json.dumps(manifest))

    def rewrite_csv(self, change):
        artifact = "search-routing-uniform-100000-100"
        relative = "stable/shards.csv"
        path = self.root / "raw" / artifact / relative
        with path.open(newline="") as source:
            reader = csv.DictReader(source)
            fields, rows = reader.fieldnames, list(reader)
        change(fields, rows)
        with path.open("w", newline="") as target:
            writer = csv.DictWriter(target, fields)
            writer.writeheader()
            writer.writerows(rows)
        self.refresh_digest(artifact, relative)

    def test_retains_every_original_observation(self):
        rows = SUMMARY.validate_and_load(self.root)
        self.assertEqual(48, len(rows))
        for artifact in sorted((self.root / "raw").iterdir()):
            for variant in ("stable", "next"):
                with (artifact / variant / "shards.csv").open(newline="") as source:
                    original = list(csv.DictReader(source))
                actual = [row for row in rows if row["artifact"] == artifact.name
                          and row["lucene_variant"] == variant]
                self.assertEqual(original, [{key: row[key] for key in original[0]}
                                            for row in actual])

    def test_rejects_unverified_raw_bytes(self):
        path = next((self.root / "raw").glob("*/stable/shards.csv"))
        path.write_text(path.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "digest|size"):
            SUMMARY.validate_and_load(self.root)

    def test_rejects_incomplete_matrix(self):
        self.rewrite_csv(lambda fields, rows: rows.pop())
        with self.assertRaisesRegex(ValueError, "matrix|coordinate"):
            SUMMARY.validate_and_load(self.root)

    def test_rejects_malformed_measurements_even_with_matching_digest(self):
        invalid = {
            "indexing_ms": "-1", "indexed_docs_per_second": "NaN",
            "text_fanout_p95_us": "Infinity", "text_routed_p50_us": "99999999",
            "peak_sampled_jvm_heap_bytes": "1073741825", "occupied_shards": "0",
            "documents_per_shard": "1;2", "purged_docs": "99999999",
            "repositories": "101", "initial_documents": "1000000",
        }
        for key, value in invalid.items():
            with self.subTest(key=key):
                # Restore the verified input before each independent corruption.
                source = EVIDENCE / "raw/search-routing-uniform-100000-100/stable/shards.csv"
                target = self.root / "raw/search-routing-uniform-100000-100/stable/shards.csv"
                shutil.copyfile(source, target)
                self.rewrite_csv(lambda fields, rows: rows[1].__setitem__(key, value))
                with self.assertRaises(ValueError):
                    SUMMARY.validate_and_load(self.root)

    def test_rejects_measurements_from_another_source(self):
        artifact = "search-routing-uniform-100000-100"
        relative = "stable/environment.properties"
        path = self.root / "raw" / artifact / relative
        path.write_text(path.read_text().replace("413fed75", "00000000"))
        self.refresh_digest(artifact, relative)
        with self.assertRaisesRegex(ValueError, "source"):
            SUMMARY.validate_and_load(self.root)


if __name__ == "__main__":
    unittest.main()
