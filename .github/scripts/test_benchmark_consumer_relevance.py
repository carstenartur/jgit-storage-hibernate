#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("benchmark_consumer_relevance.py")
SPEC = importlib.util.spec_from_file_location("benchmark_consumer_relevance", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BenchmarkConsumerRelevanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        github = self.root / ".github"
        github.mkdir()
        (github / "consumer-compatibility.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "consumers": [
                        {
                            "id": "audio-analyzer",
                            "displayName": "audio-analyzer",
                            "repository": "example/audio",
                            "ref": "a" * 40,
                            "defaultBranch": "master",
                            "contractScript": ".github/contract.sh",
                            "expectedModules": [
                                "jgit-storage-hibernate-core",
                                "jgit-storage-hibernate-search",
                            ],
                        },
                        {
                            "id": "taxonomy",
                            "displayName": "Taxonomy",
                            "repository": "example/taxonomy",
                            "ref": "b" * 40,
                            "defaultBranch": "main",
                            "contractScript": ".github/contract.sh",
                            "expectedModules": ["jgit-storage-hibernate-core"],
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )
        (github / "benchmark-consumer-relevance.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "suites": {
                        "Core": {
                            "contract": "core-path",
                            "requiredModules": ["jgit-storage-hibernate-core"],
                        },
                        "Search": {
                            "contract": "search-path",
                            "requiredModules": ["jgit-storage-hibernate-search"],
                        },
                    },
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_derives_consumers_from_required_modules(self) -> None:
        catalog, rules = MODULE.load_contracts(self.root)
        self.assertEqual(["audio-analyzer", "taxonomy"], rules["Core"]["consumers"])
        self.assertEqual(["audio-analyzer"], rules["Search"]["consumers"])
        benches = [{"name": "query", "unit": "ms/op", "value": 1.0}]
        evidence = MODULE.annotate_benchmarks(benches, "Search", catalog, rules)
        self.assertEqual(["audio-analyzer"], benches[0]["consumers"])
        self.assertEqual("search-path", benches[0]["contract"])
        self.assertEqual("a" * 40, evidence["audio-analyzer"]["ref"])

    def test_leaves_history_unclassified_when_descriptors_are_absent(self) -> None:
        empty = Path(self.temp.name) / "empty"
        empty.mkdir()
        self.assertEqual(({}, {}), MODULE.load_contracts(empty))

    def test_rejects_suite_without_mapping(self) -> None:
        catalog, rules = MODULE.load_contracts(self.root)
        with self.assertRaisesRegex(ValueError, "has no consumer relevance rule"):
            MODULE.annotate_benchmarks(
                [{"name": "x", "unit": "ms/op", "value": 1.0}],
                "Missing",
                catalog,
                rules,
            )

    def test_postprocesses_current_entry_without_rewriting_old_points(self) -> None:
        catalog, rules = MODULE.load_contracts(self.root)
        data_file = self.root / "data.js"
        old = {"commit": {"id": "old"}, "benches": [{"name": "old", "value": 2}]}
        current = {"commit": {"id": "new"}, "benches": [{"name": "new", "value": 1}]}
        data_file.write_text(
            MODULE.SCRIPT_PREFIX
            + json.dumps({"entries": {"Search": [old, current]}}),
            encoding="utf-8",
        )
        MODULE.postprocess_history(data_file, "Search", "new", catalog, rules)
        parsed = json.loads(data_file.read_text()[len(MODULE.SCRIPT_PREFIX) :])
        self.assertNotIn("consumerEvidence", parsed["entries"]["Search"][0])
        self.assertEqual(
            ["audio-analyzer"],
            list(parsed["entries"]["Search"][1]["consumerEvidence"]),
        )
        self.assertEqual(1, parsed["consumerRelevanceSchemaVersion"])


if __name__ == "__main__":
    unittest.main()
