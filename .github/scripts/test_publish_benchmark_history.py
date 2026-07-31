#!/usr/bin/env python3
"""Tests for publish-benchmark-history.py."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("publish-benchmark-history.py")
PREFIX = "window.BENCHMARK_DATA = "


class PublishBenchmarkHistoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repository)], check=True)
        subprocess.run(["git", "-C", str(self.repository), "config", "user.name", "Test Author"], check=True)
        subprocess.run(
            ["git", "-C", str(self.repository), "config", "user.email", "author@example.test"],
            check=True,
        )
        (self.repository / "README.md").write_text("test\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.repository), "add", "README.md"], check=True)
        subprocess.run(["git", "-C", str(self.repository), "commit", "-q", "-m", "Initial test commit"], check=True)
        self.commit = subprocess.run(
            ["git", "-C", str(self.repository), "rev-parse", "HEAD"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()
        self.benchmark_file = self.root / "benchmarks.json"
        self.data_file = self.root / "pages" / "dev" / "bench" / "data.js"
        self.summary_file = self.root / "summary.md"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _write_benchmarks(
        self,
        first_value: float = 1.25,
        first_unit: str = "ms/op",
        first_range: float = 0.1,
    ) -> None:
        self.benchmark_file.write_text(
            json.dumps(
                [
                    {
                        "name": "read",
                        "unit": first_unit,
                        "value": first_value,
                        "range": first_range,
                    },
                    {"name": "write", "unit": "ms/op", "value": 2.5},
                ]
            ),
            encoding="utf-8",
        )

    def _run(self, *, timestamp: int = 1234567890000, max_items: int = 100) -> None:
        subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--data-file",
                str(self.data_file),
                "--benchmark-file",
                str(self.benchmark_file),
                "--repository-dir",
                str(self.repository),
                "--repository-url",
                "https://github.com/example/project",
                "--commit",
                self.commit,
                "--actor",
                "test-user",
                "--max-items",
                str(max_items),
                "--timestamp-ms",
                str(timestamp),
                "--summary-file",
                str(self.summary_file),
            ],
            check=True,
        )

    def _read_data(self) -> dict:
        content = self.data_file.read_text(encoding="utf-8")
        self.assertTrue(content.startswith(PREFIX))
        return json.loads(content[len(PREFIX) :])

    def test_creates_history_and_commit_metadata(self) -> None:
        self._write_benchmarks()
        self._run()

        data = self._read_data()
        self.assertEqual(1234567890000, data["lastUpdate"])
        self.assertEqual("https://github.com/example/project", data["repoUrl"])
        history = data["entries"]["Repository backend comparison"]
        self.assertEqual(1, len(history))
        entry = history[0]
        self.assertEqual(self.commit, entry["commit"]["id"])
        self.assertEqual("Initial test commit", entry["commit"]["message"])
        self.assertEqual("test-user", entry["commit"]["author"]["username"])
        self.assertEqual("customSmallerIsBetter", entry["tool"])
        self.assertEqual(2, len(entry["benches"]))
        self.assertTrue(all(bench["unit"] == "ms/op" for bench in entry["benches"]))
        self.assertIn("Stored the first comparable benchmark result", self.summary_file.read_text(encoding="utf-8"))

    def test_normalizes_current_throughput_input(self) -> None:
        self._write_benchmarks(first_value=100.0, first_unit="ops/s", first_range=10.0)
        self._run()

        read = self._read_data()["entries"]["Repository backend comparison"][0]["benches"][0]
        self.assertEqual("ms/op", read["unit"])
        self.assertAlmostEqual(10.0, read["value"])
        self.assertAlmostEqual(1.0, read["range"])
        self.assertIn("Original metric: 100.0 ops/s", read["extra"])

    def test_migrates_legacy_throughput_history_before_comparison(self) -> None:
        existing = {
            "lastUpdate": 1,
            "repoUrl": "https://github.com/example/project",
            "entries": {
                "Repository backend comparison": [
                    {
                        "commit": {"id": "old-throughput"},
                        "date": 1,
                        "tool": "customBiggerIsBetter",
                        "benches": [
                            {
                                "name": "read",
                                "unit": "ops/s",
                                "value": 100.0,
                                "range": 10.0,
                            }
                        ],
                    }
                ]
            },
        }
        self.data_file.parent.mkdir(parents=True)
        self.data_file.write_text(PREFIX + json.dumps(existing), encoding="utf-8")
        self._write_benchmarks(first_value=12.0)
        self._run()

        history = self._read_data()["entries"]["Repository backend comparison"]
        self.assertEqual(2, len(history))
        previous = history[0]
        self.assertEqual("customSmallerIsBetter", previous["tool"])
        self.assertEqual("ms/op", previous["benches"][0]["unit"])
        self.assertAlmostEqual(10.0, previous["benches"][0]["value"])
        self.assertAlmostEqual(1.0, previous["benches"][0]["range"])

        summary = self.summary_file.read_text(encoding="utf-8")
        self.assertIn("12.0 ms/op", summary)
        self.assertIn("10.0 ms/op", summary)
        self.assertIn("1.20×", summary)

    def test_replaces_same_commit_instead_of_duplicating_it(self) -> None:
        self._write_benchmarks(1.0)
        self._run()
        self._write_benchmarks(3.0)
        self._run(timestamp=1234567890001)

        history = self._read_data()["entries"]["Repository backend comparison"]
        self.assertEqual(1, len(history))
        self.assertEqual(3.0, history[0]["benches"][0]["value"])
        self.assertEqual(1234567890001, history[0]["date"])

    def test_preserves_other_suites_and_applies_maximum(self) -> None:
        existing = {
            "lastUpdate": 1,
            "repoUrl": "https://github.com/example/project",
            "entries": {
                "Other suite": [{"commit": {"id": "other"}, "benches": []}],
                "Repository backend comparison": [
                    {"commit": {"id": "old-1"}, "date": 1, "tool": "customSmallerIsBetter", "benches": []},
                    {"commit": {"id": "old-2"}, "date": 2, "tool": "customSmallerIsBetter", "benches": []},
                ],
            },
        }
        self.data_file.parent.mkdir(parents=True)
        self.data_file.write_text(PREFIX + json.dumps(existing), encoding="utf-8")
        self._write_benchmarks()
        self._run(max_items=2)

        data = self._read_data()
        self.assertIn("Other suite", data["entries"])
        history = data["entries"]["Repository backend comparison"]
        self.assertEqual(["old-2", self.commit], [entry["commit"]["id"] for entry in history])

    def test_rejects_malformed_benchmark_input(self) -> None:
        self.benchmark_file.write_text(json.dumps([{"name": "missing fields"}]), encoding="utf-8")
        completed = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--data-file",
                str(self.data_file),
                "--benchmark-file",
                str(self.benchmark_file),
                "--repository-dir",
                str(self.repository),
                "--repository-url",
                "https://github.com/example/project",
                "--commit",
                self.commit,
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("missing 'unit'", completed.stderr)


if __name__ == "__main__":
    unittest.main()
