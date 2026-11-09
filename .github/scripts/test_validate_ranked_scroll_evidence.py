#!/usr/bin/env python3
"""Exercise the hosted ranked-scroll evidence gate using native-format fixtures."""

from __future__ import annotations

import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate_ranked_scroll_evidence.py")
COMMIT = "a" * 40
SOURCES = (
    "reproducers/hibernate-search-ranked-scroll/pom.xml",
    "reproducers/hibernate-search-ranked-scroll/src/main/java/io/github/carstenartur/reproducer/RankedScrollMemoryProbe.java",
    "reproducers/hibernate-search-ranked-scroll/logging.properties",
    ".github/workflows/search-ranked-scroll-memory.yml",
    ".github/scripts/validate_ranked_scroll_evidence.py",
    ".github/scripts/validate_search_database_telemetry.py",
)


class RankedScrollEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "source"
        self.output = self.root / "output"
        self.output.mkdir()
        for name in SOURCES:
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic source\n", encoding="utf-8")
        self.jars = []
        for name in (
            "hibernate-search-mapper-orm-8.4.0.Final.jar",
            "hibernate-search-mapper-pojo-base-8.4.0.Final.jar",
            "hibernate-search-engine-8.4.0.Final.jar",
            "hibernate-search-backend-lucene-8.4.0.Final.jar",
            "hibernate-search-util-common-8.4.0.Final.jar",
            "hibernate-core-7.4.14.Final.jar",
            "lucene-core-9.12.3.jar",
        ):
            path = self.root / name
            path.write_bytes(b"synthetic dependency\n")
            self.jars.append(path)
        (self.output / "runtime-classpath.txt").write_text(":".join(map(str, self.jars)))
        for name in ("dependency-tree.txt", "console.txt", "maven-build.log",
                     "maven-version.txt", "java-version.txt"):
            (self.output / name).write_text("synthetic successful run\n")
        self.environment = {
            "java": "21.0.13+9-LTS", "hibernate-search": "8.4.0.Final",
            "hibernate": "7.4.14.Final", "lucene": "9.12.3", "documents": "1024",
            "chunk-size": "128", "maximum-heap-bytes": "536870912",
            "retained-hit-budget": "unset", "entity-loads": "0", "prepared-statements": "0",
            "sessions-opened": "5", "sessions-closed": "5",
        }
        self.rows = [
            ["before_open", 0, 0, 0, 0], ["chunk_1", 128, 512, 16384, 2096],
            ["chunk_2", 256, 512, 16384, 2096], ["chunk_4", 512, 512, 16384, 2096],
            ["chunk_8", 1024, 512, 16384, 2096], ["after_close", 1024, 0, 0, 32],
        ]
        self.write_observations()
        recorded = self.run_gate("record")
        self.assertEqual(0, recorded.returncode, recorded.stderr)

    def write_observations(self):
        (self.output / "environment.txt").write_text(
            "".join(f"{key}={value}\n" for key, value in self.environment.items())
        )
        with (self.output / "retained-hits.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow([
                "phase", "consumed_rows", "live_field_docs", "field_doc_shallow_bytes",
                "score_doc_array_shallow_bytes",
            ])
            writer.writerows(self.rows)
        for phase, _, count, hit_bytes, array_bytes in self.rows:
            lines = [" num     #instances         #bytes  class name (module)", "---"]
            if count:
                lines.append(f" 1: {count} {hit_bytes} org.apache.lucene.search.FieldDoc")
            if array_bytes:
                lines.append(f" 2: 3 {array_bytes} [Lorg.apache.lucene.search.ScoreDoc;")
            lines.append("Total 10000 1000000")
            (self.output / f"{phase}.histogram.txt").write_text("\n".join(lines) + "\n")

    def run_gate(self, command="validate"):
        return subprocess.run(
            [sys.executable, str(SCRIPT), command, str(self.output),
             "--source-root", str(self.source), "--commit", COMMIT,
             "--documents", "1024", "--search-version", "8.4.0.Final"],
            text=True, capture_output=True, check=False,
        )

    def reject(self, expected):
        result = self.run_gate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(expected, result.stderr)

    def test_accepts_complete_observations_without_imposing_a_hit_budget(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1024 rows", result.stdout)

    def test_rejects_other_jdk_build(self):
        self.environment["java"] = "21.0.13+90-LTS"
        self.write_observations()
        self.reject("JDK")

    def test_rejects_other_search_version(self):
        self.environment["hibernate-search"] = "8.4.1.Final"
        self.write_observations()
        self.reject("Search version")

    def test_rejects_incomplete_traversal(self):
        self.rows[-1][1] = 1023
        self.write_observations()
        self.reject("checkpoint")

    def test_rejects_missing_checkpoint(self):
        self.rows.pop(3)
        self.write_observations()
        self.reject("checkpoint")

    def test_rejects_histogram_and_csv_disagreement(self):
        path = self.output / "chunk_1.histogram.txt"
        path.write_text(path.read_text().replace("512 16384", "513 16384"))
        self.reject("histogram")

    def test_rejects_hits_left_after_close(self):
        self.rows[-1][2:4] = [1, 32]
        self.write_observations()
        self.reject("after close")

    def test_rejects_unclosed_session(self):
        self.environment["sessions-closed"] = "4"
        self.write_observations()
        self.reject("session")

    def test_rejects_entity_hydration(self):
        self.environment["entity-loads"] = "1"
        self.write_observations()
        self.reject("entity loads")

    def test_rejects_changed_runtime_jar(self):
        self.jars[0].write_bytes(b"changed dependency")
        self.reject("provenance")

    def test_rejects_changed_source(self):
        (self.source / SOURCES[0]).write_text("changed source")
        self.reject("provenance")


if __name__ == "__main__":
    unittest.main()
