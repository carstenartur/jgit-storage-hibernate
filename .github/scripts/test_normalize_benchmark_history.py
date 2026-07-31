#!/usr/bin/env python3
"""Tests for normalize-benchmark-history.py."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("normalize-benchmark-history.py")
SPEC = importlib.util.spec_from_file_location("normalize_benchmark_history", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class NormalizeBenchmarkHistoryTest(unittest.TestCase):
    def test_migrates_throughput_points_and_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            data_file = Path(directory) / "data.js"
            data = {
                "lastUpdate": 1,
                "repoUrl": "https://github.com/example/project",
                "entries": {
                    "Repository backend comparison": [
                        {
                            "commit": {"id": "old"},
                            "date": 1,
                            "tool": "customSmallerIsBetter",
                            "benches": [
                                {
                                    "name": "publish — PostgreSQL",
                                    "unit": "ops/s",
                                    "value": 50.0,
                                    "range": "5.0",
                                    "extra": "Mode: thrpt",
                                }
                            ],
                        },
                        {
                            "commit": {"id": "new"},
                            "date": 2,
                            "tool": "customSmallerIsBetter",
                            "benches": [
                                {
                                    "name": "publish — PostgreSQL",
                                    "unit": "ms/op",
                                    "value": 18.0,
                                    "range": 1.0,
                                }
                            ],
                        },
                    ]
                },
            }
            data_file.write_text(
                MODULE.SCRIPT_PREFIX + json.dumps(data), encoding="utf-8"
            )

            self.assertTrue(MODULE.normalize_file(data_file))
            first_content = data_file.read_text(encoding="utf-8")
            self.assertFalse(MODULE.normalize_file(data_file))
            self.assertEqual(first_content, data_file.read_text(encoding="utf-8"))

            normalized = json.loads(first_content[len(MODULE.SCRIPT_PREFIX) :])
            benches = normalized["entries"]["Repository backend comparison"]
            old = benches[0]["benches"][0]
            new = benches[1]["benches"][0]
            self.assertEqual("ms/op", old["unit"])
            self.assertAlmostEqual(20.0, old["value"])
            self.assertAlmostEqual(2.0, old["range"])
            self.assertIn("normalized from ops/s", old["extra"])
            self.assertEqual("ms/op", new["unit"])
            self.assertAlmostEqual(18.0, new["value"])

    def test_missing_history_file_is_a_no_op(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(MODULE.normalize_file(Path(directory) / "missing.js"))

    def test_rejects_unsupported_units(self) -> None:
        data = {
            "entries": {
                "suite": [
                    {
                        "benches": [
                            {"name": "invalid", "unit": "bananas", "value": 1.0}
                        ]
                    }
                ]
            }
        }
        with self.assertRaisesRegex(ValueError, "Unsupported benchmark unit"):
            MODULE.normalize_history(data)


if __name__ == "__main__":
    unittest.main()
