#!/usr/bin/env python3
"""Contract tests for the bounded, complete performance-investigation workflow."""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/performance-investigations.yml"
BENCHMARK_IT = (
    ROOT
    / "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PerformanceInvestigationsBenchmarkIT.java"
)


class PerformanceInvestigationsWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.benchmark_it = BENCHMARK_IT.read_text(encoding="utf-8")

    def section(self, start: str, end: str) -> str:
        return self.workflow.split(start, 1)[1].split(end, 1)[0]

    def test_generic_matrix_does_not_repeat_the_unbounded_aging_matrix(self) -> None:
        generic = self.section("  investigate:\n", "  repository-aging-smoke:\n")
        self.assertIn("- concurrent-large-pack", generic)
        self.assertNotIn("- repository-aging", generic)

    def test_full_aging_matrix_is_split_into_six_disjoint_shards(self) -> None:
        shards = self.section(
            "  repository-aging-full-shard:\n",
            "  repository-aging-full-summary:\n",
        )
        for fragment in (
            "backend:\n          - hsqldb\n          - postgresql\n          - postgresql-hikari",
            "cache_state:\n          - cold\n          - warm",
            "jgit.storage.benchmark.repository-aging.backend=${{ matrix.backend }}",
            "jgit.storage.benchmark.repository-aging.cache-state=${{ matrix.cache_state }}",
            "performance-investigation-repository-aging-full-${{ matrix.backend }}-${{ matrix.cache_state }}",
        ):
            self.assertIn(fragment, shards)
        self.assertIn("timeout-minutes: 90", shards)

    def test_full_summary_requires_every_shard_and_validates_the_merged_matrix(self) -> None:
        summary = self.section(
            "  repository-aging-full-summary:\n", "  stateless-threshold:\n"
        )
        for fragment in (
            "needs: repository-aging-full-shard",
            "needs['repository-aging-full-shard'].result == 'success'",
            "actions/download-artifact@484a0b528fb4d7bd804637ccb632e47a0e638317",
            "pattern: performance-investigation-repository-aging-full-*",
            "merge-multiple: true",
            "for backend in ('hsqldb', 'postgresql', 'postgresql-hikari')",
            "for cache in ('cold', 'warm')",
            "Expected six repository-aging shards",
            "convert-jmh-repository-aging.py",
            "target/investigations/repository-aging/jmh-result.json",
        ):
            self.assertIn(fragment, summary)
        self.assertLess(
            summary.index("Merge the six disjoint JMH result sets"),
            summary.index("Derive complete repository-aging policy evidence"),
        )

    def test_transient_maven_central_throttling_is_retried(self) -> None:
        retry = "-Dmaven.wagon.http.retryHandler.count=5"
        self.assertGreaterEqual(self.workflow.count(retry), 6)

    def test_java_runner_supports_validated_backend_and_cache_shards(self) -> None:
        for fragment in (
            "jgit.storage.benchmark.repository-aging.backend",
            "jgit.storage.benchmark.repository-aging.cache-state",
            "static String[] selectParameterValues(",
            "return defaultValues.clone();",
            "List.of(allowedValues).contains(value)",
            '.param("backend", backends)',
            '.param("cacheState", cacheStates)',
        ):
            self.assertIn(fragment, self.benchmark_it)


if __name__ == "__main__":
    unittest.main()
