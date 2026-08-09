#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("summarize-consumer-compatibility.py")
SPEC = importlib.util.spec_from_file_location("summarize_consumer_compatibility", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ConsumerCompatibilitySummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.descriptor = self.root / "consumers.json"
        self.artifacts = self.root / "artifacts"
        self.descriptor.write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "consumers": [
                        {
                            "id": "audio-analyzer",
                            "displayName": "Audio Analyzer",
                            "repository": "example/audio",
                            "ref": "a" * 40,
                            "defaultBranch": "master",
                            "contractScript": ".github/contract.sh",
                            "expectedModules": [
                                "jgit-storage-hibernate-core",
                                "jgit-storage-hibernate-search",
                            ],
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_json(self, relative: str, value: object) -> None:
        path = self.artifacts / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def test_reports_exact_versions_modules_commit_duration_and_contract(self) -> None:
        base = "consumer-candidate-audio-analyzer"
        self.write_json(
            f"{base}/substitution.json",
            {
                "candidateVersion": "0.9.1-SNAPSHOT",
                "literalChanges": [],
                "propertyChanges": [{"from": "0.9.0", "to": "0.9.1-SNAPSHOT"}],
            },
        )
        self.write_json(
            f"{base}/result.json",
            {"contract": "audio-app verification and packaged-runtime linkage"},
        )
        self.write_json(f"{base}/run-metadata.json", {"durationSeconds": 125})
        commit = self.artifacts / base / "consumer-commit.txt"
        commit.write_text("b" * 40 + "\n", encoding="utf-8")
        self.write_json(
            "consumer-baseline-audio-analyzer/result.json",
            {"contract": "baseline"},
        )
        self.write_json(
            "consumer-baseline-audio-analyzer/run-metadata.json",
            {"durationSeconds": 59},
        )

        summary = MODULE.build_summary(
            self.descriptor, self.artifacts, "https://github.example/actions/runs/1"
        )
        self.assertIn("Audio Analyzer", summary)
        self.assertIn("`bbbbbbb`", summary)
        self.assertIn("`0.9.0` → `0.9.1-SNAPSHOT`", summary)
        self.assertIn("passed (2m 5s)", summary)
        self.assertIn("passed (59s)", summary)
        self.assertIn("jgit-storage-hibernate-search", summary)
        self.assertIn("audio-app verification", summary)
        self.assertIn("Open retained compatibility artifacts", summary)

    def test_marks_missing_or_incomplete_artifacts_without_inventing_success(self) -> None:
        self.artifacts.mkdir()
        summary = MODULE.build_summary(self.descriptor, self.artifacts, "")
        self.assertIn("not run (—)", summary)
        self.assertNotIn("passed", summary)

    def test_accepts_property_and_literal_original_versions(self) -> None:
        self.write_json(
            "consumer-candidate-audio-analyzer/substitution.json",
            {
                "candidateVersion": "2.0.0-SNAPSHOT",
                "literalChanges": [{"from": "1.0.0"}],
                "propertyChanges": [{"from": "1.1.0"}],
            },
        )
        summary = MODULE.build_summary(self.descriptor, self.artifacts, "")
        self.assertIn("`1.0.0, 1.1.0` → `2.0.0-SNAPSHOT`", summary)


if __name__ == "__main__":
    unittest.main()
