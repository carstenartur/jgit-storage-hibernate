#!/usr/bin/env python3
"""Regression tests for benchmark_consumer_relevance.py."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import benchmark_consumer_relevance as relevance


class BenchmarkConsumerRelevanceTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.consumers = self.root / "consumers.json"
        self.mapping = self.root / "mapping.json"
        self.consumers.write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "consumers": [
                        {
                            "id": "audio-analyzer",
                            "ref": "a" * 40,
                            "expectedModules": [
                                "jgit-storage-hibernate-core",
                                "jgit-storage-hibernate-search",
                            ],
                        },
                        {
                            "id": "taxonomy",
                            "ref": "b" * 40,
                            "expectedModules": ["jgit-storage-hibernate-core"],
                        },
                        {
                            "id": "sandbox",
                            "ref": "c" * 40,
                            "expectedModules": ["jgit-storage-hibernate-core"],
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )
        self.mapping.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "suites": {
                        "Core": {
                            "contract": "core-storage",
                            "requiredModules": ["jgit-storage-hibernate-core"],
                        },
                        "Search": {
                            "contract": "search-history",
                            "requiredModules": [
                                "jgit-storage-hibernate-core",
                                "jgit-storage-hibernate-search",
                            ],
                        },
                    },
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_core_suite_is_relevant_to_all_current_consumers(self) -> None:
        metadata = relevance.resolve(self.consumers, self.mapping, "Core")
        self.assertIsNotNone(metadata)
        self.assertEqual(
            ["audio-analyzer", "sandbox", "taxonomy"], metadata["consumers"]
        )
        self.assertEqual("core-storage", metadata["contract"])

    def test_search_suite_is_not_guessed_for_core_only_consumers(self) -> None:
        metadata = relevance.resolve(self.consumers, self.mapping, "Search")
        self.assertEqual(["audio-analyzer"], metadata["consumers"])
        evidence = {item["consumer"]: item for item in metadata["consumerRelevanceEvidence"]}
        self.assertTrue(evidence["audio-analyzer"]["relevant"])
        self.assertFalse(evidence["taxonomy"]["relevant"])
        self.assertFalse(evidence["sandbox"]["relevant"])

    def test_unknown_suite_remains_unclassified(self) -> None:
        self.assertIsNone(relevance.resolve(self.consumers, self.mapping, "Unknown"))

    def test_enrichment_does_not_change_measurement_fields(self) -> None:
        benches = [{"name": "query — backend", "unit": "ms/op", "value": 3.5}]
        metadata = relevance.resolve(self.consumers, self.mapping, "Search")
        enriched = relevance.apply_to_benches(benches, metadata)
        self.assertEqual(3.5, enriched[0]["value"])
        self.assertEqual("ms/op", enriched[0]["unit"])
        self.assertEqual(["audio-analyzer"], enriched[0]["consumers"])
        self.assertNotIn("consumers", benches[0])


if __name__ == "__main__":
    unittest.main()
