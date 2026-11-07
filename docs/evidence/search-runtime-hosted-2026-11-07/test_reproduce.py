#!/usr/bin/env python3
"""Evidence-contract regressions; no database, JVM or benchmark execution."""

import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("runtime_evidence", ROOT / "reproduce.py")
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)


class RuntimeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(ROOT / "raw", self.root / "raw")
        shutil.copyfile(ROOT / "provenance.json", self.root / "provenance.json")

    def manifest(self):
        return json.loads((self.root / "provenance.json").read_text())

    def save_manifest(self, manifest):
        (self.root / "provenance.json").write_text(json.dumps(manifest))

    def rewrite_jmh(self, mutate):
        manifest = self.manifest()
        artifact = next(item for item in manifest["artifacts"]
                        if item["cell"] == "10000-50-normal-smoke")
        filename = "jmh-result.json.gz"
        target = self.root / "raw" / artifact["cell"] / filename
        original = ROOT / "raw" / artifact["cell"] / filename
        rows = json.loads(gzip.decompress(original.read_bytes()))
        mutate(rows)
        raw = json.dumps(rows).encode()
        stored = gzip.compress(raw, mtime=0)
        target.write_bytes(stored)
        artifact["files"][filename].update({"sha256": hashlib.sha256(stored).hexdigest(),
                                            "bytes": len(stored),
                                            "original_sha256": hashlib.sha256(raw).hexdigest(),
                                            "original_bytes": len(raw)})
        self.save_manifest(manifest)

    def test_preserves_all_available_invocations(self):
        expected = sum(164 if item["cell"].endswith("full") else 28
                       for item in self.manifest()["artifacts"])
        self.assertEqual(expected, len(EVIDENCE.load_data(self.root, allow_partial=True)))

    def test_default_rejects_partial_matrix(self):
        manifest = self.manifest()
        manifest["artifacts"] = manifest["artifacts"][:1]
        self.save_manifest(manifest)
        with self.assertRaisesRegex(ValueError, "matrix|cell"):
            EVIDENCE.load_data(self.root)

    def test_gc_time_keeps_reported_values_without_inventing_zero_shots(self):
        def sparse_gc(rows):
            rows[0]["secondaryMetrics"]["gc.time"]["rawData"] = [[12.0]]
        self.rewrite_jmh(sparse_gc)
        rows = EVIDENCE.load_data(self.root, allow_partial=True)
        first = next(row for row in rows if row["cell"] == "10000-50-normal-smoke"
                     and row["scenario"] == "reference" and row["operation"] == "incrementalBurstReady")
        self.assertEqual([12.0], first["secondary"]["gc.time"])
        self.assertEqual(2, len(first["primary"]))

    def test_auxiliary_means_come_from_raw_shots(self):
        rows = EVIDENCE.load_data(self.root, allow_partial=True)
        ready = next(row for row in rows if row["cell"] == "10000-50-normal-smoke"
                     and row["scenario"] == "reference" and row["operation"] == "incrementalBurstReady")
        summary = next(row for row in EVIDENCE.summarize(rows)
                       if row["cell"] == ready["cell"] and row["scenario"] == ready["scenario"])
        self.assertAlmostEqual(sum(ready["secondary"]["visibilityWaitMicros"]) / 2 / 1000,
                               summary["visibility_wait_ms_mean"])
        self.assertAlmostEqual(50 * 1000 / summary["submission_ms_mean"],
                               summary["submission_commits_per_second"])

    def test_checks_retained_bytes_before_using_them(self):
        path = next((self.root / "raw").glob("*/jmh-result.json.gz"))
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "digest|size"):
            EVIDENCE.load_data(self.root, allow_partial=True)

    def test_rejects_failed_job_even_when_measurements_are_present(self):
        manifest = self.manifest()
        for job in manifest["jobs"]:
            if "10000-50" in job["name"]:
                job["conclusion"] = "failure"
        self.save_manifest(manifest)
        with self.assertRaisesRegex(ValueError, "successful|success|job"):
            EVIDENCE.load_data(self.root, allow_partial=True)

    def test_rejects_invalid_shots_with_fresh_digests(self):
        changes = [
            lambda r: r[0]["primaryMetric"].__setitem__("scoreUnit", "s/op"),
            lambda r: r[0]["primaryMetric"]["rawData"][0].__setitem__(0, -1),
            lambda r: r[0]["primaryMetric"]["rawData"][0].pop(),
            lambda r: r[0]["secondaryMetrics"]["peakHeapPoolBytes"]["rawData"][0].__setitem__(0, "NaN"),
            lambda r: r[0]["secondaryMetrics"].pop("luceneCommittedSegments"),
            lambda r: r[0]["secondaryMetrics"]["luceneCommittedDocuments"]["rawData"][0].__setitem__(0, 9999),
            lambda r: r[0].__setitem__("measurementIterations", 3),
            lambda r: r.append(r[0]),
        ]
        for number, change in enumerate(changes):
            with self.subTest(case=number):
                self.rewrite_jmh(change)
                with self.assertRaises(ValueError):
                    EVIDENCE.load_data(self.root, allow_partial=True)


if __name__ == "__main__":
    unittest.main()
