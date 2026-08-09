#!/usr/bin/env python3
"""Tests for verify-benchmark-consumer-relevance.py."""

from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-benchmark-consumer-relevance.py")
PUBLISHER = Path(__file__).with_name("publish-benchmark-history.py")
PUBLISHER_CORE = Path(__file__).with_name("publish-benchmark-history-core.py")
RELEVANCE = Path(__file__).with_name("benchmark_consumer_relevance.py")
UNITS = Path(__file__).with_name("benchmark_units.py")
SPEC = importlib.util.spec_from_file_location("verify_benchmark_consumer_relevance", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class BenchmarkConsumerRelevanceVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        scripts = self.root / ".github" / "scripts"
        workflows = self.root / ".github" / "workflows"
        scripts.mkdir(parents=True)
        workflows.mkdir(parents=True)
        shutil.copy(PUBLISHER, scripts / PUBLISHER.name)
        shutil.copy(PUBLISHER_CORE, scripts / PUBLISHER_CORE.name)
        shutil.copy(RELEVANCE, scripts / RELEVANCE.name)
        shutil.copy(UNITS, scripts / UNITS.name)
        (self.root / ".github" / "consumer-compatibility.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "consumers": [
                        {
                            "id": "consumer",
                            "displayName": "Consumer",
                            "repository": "example/consumer",
                            "ref": "a" * 40,
                            "defaultBranch": "main",
                            "contractScript": ".github/contract.sh",
                            "expectedModules": ["jgit-storage-hibernate-core"],
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        self.mapping = self.root / ".github" / "benchmark-consumer-relevance.json"
        self.mapping.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "suites": {
                        "Repository backend comparison": {
                            "contract": "core",
                            "requiredModules": ["jgit-storage-hibernate-core"],
                        },
                        "Explicit suite": {
                            "contract": "core-explicit",
                            "requiredModules": ["jgit-storage-hibernate-core"],
                        },
                    },
                }
            ),
            encoding="utf-8",
        )
        (workflows / "performance.yml").write_text(
            """
            run: |
              python3 .github/scripts/publish-benchmark-history.py \\
                --data-file default.js
              python3 .github/scripts/publish-benchmark-history.py \\
                --suite-name 'Explicit suite' \\
                --data-file explicit.js
            """,
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_accepts_complete_mapping_and_emits_derived_evidence(self) -> None:
        evidence = VERIFIER.verify(self.root)
        self.assertEqual(
            ["Explicit suite", "Repository backend comparison"],
            [suite["suite"] for suite in evidence["suites"]],
        )
        self.assertEqual(["consumer"], evidence["suites"][0]["consumers"])

    def test_rejects_published_suite_without_mapping(self) -> None:
        data = json.loads(self.mapping.read_text(encoding="utf-8"))
        del data["suites"]["Explicit suite"]
        self.mapping.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "published suites without mappings"):
            VERIFIER.verify(self.root)

    def test_rejects_unused_mapping(self) -> None:
        data = json.loads(self.mapping.read_text(encoding="utf-8"))
        data["suites"]["Unused suite"] = {
            "contract": "unused",
            "requiredModules": ["jgit-storage-hibernate-core"],
        }
        self.mapping.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "mappings without publisher invocations"):
            VERIFIER.verify(self.root)


if __name__ == "__main__":
    unittest.main()
