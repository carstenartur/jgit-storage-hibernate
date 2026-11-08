#!/usr/bin/env python3
"""Contract tests for the bounded, complete performance-investigation workflow."""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/performance-investigations.yml"
MAVEN_JVM_CONFIG = ROOT / ".mvn/jvm.config"
BENCHMARK_IT = (
    ROOT
    / "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PerformanceInvestigationsBenchmarkIT.java"
)
PARAMETER_SELECTION = (
    ROOT
    / "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PerformanceInvestigationParameters.java"
)


class PerformanceInvestigationsWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.maven_jvm_config = MAVEN_JVM_CONFIG.read_text(encoding="utf-8")
        cls.benchmark_it = BENCHMARK_IT.read_text(encoding="utf-8")
        cls.parameter_selection = PARAMETER_SELECTION.read_text(encoding="utf-8")

    def section(self, start: str, end: str) -> str:
        self.assertIn(start, self.workflow, f"Missing workflow section marker {start!r}")
        tail = self.workflow.partition(start)[2]
        self.assertIn(end, tail, f"Missing workflow section marker {end!r} after {start!r}")
        return tail.partition(end)[0]

    def test_generic_matrix_does_not_repeat_the_unbounded_aging_matrix(self) -> None:
        generic = self.section("  investigate:\n", "  repository-aging-smoke:\n")
        self.assertIn("- concurrent-large-pack", generic)
        self.assertNotIn("- repository-aging", generic)

    def test_full_aging_matrix_has_two_shards_per_backend(self) -> None:
        shards = self.section(
            "  repository-aging-full-shard:\n",
            "  repository-aging-full-summary:\n",
        )
        for fragment in (
            "backend:\n          - hsqldb\n          - postgresql\n          - postgresql-hikari\n          - sqlserver",
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
            "actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333",
            "pattern: performance-investigation-repository-aging-full-*",
            "merge-multiple: true",
            "for backend in ('hsqldb', 'postgresql', 'postgresql-hikari', 'sqlserver')",
            "for cache in ('cold', 'warm')",
            "Expected {len(expected)} repository-aging shards",
            "convert-jmh-repository-aging.py",
            "--full-matrix",
            "target/investigations/repository-aging/jmh-result.json",
        ):
            self.assertIn(fragment, summary)
        self.assertLess(
            summary.index("Merge the disjoint JMH result sets"),
            summary.index("Derive complete repository-aging policy evidence"),
        )

    def test_transient_maven_central_throttling_is_retried(self) -> None:
        for retry in (
            "-Daether.transport.http.retryHandler.count=5",
            "-Dmaven.wagon.http.retryHandler.count=5",
        ):
            self.assertIn(retry, self.maven_jvm_config)

    def test_java_runner_supports_validated_backend_and_cache_shards(self) -> None:
        for fragment in (
            "PerformanceInvestigationParameters.repositoryAgingBackends(",
            "jgit.storage.benchmark.repository-aging.cache-state",
            '.param("backend", backends)',
            '.param("cacheState", cacheStates)',
        ):
            self.assertIn(fragment, self.benchmark_it)
        for fragment in (
            "jgit.storage.benchmark.repository-aging.backend",
            "static String[] select(",
            "return defaultValues.clone();",
            "List.of(allowedValues).contains(value)",
        ):
            self.assertIn(fragment, self.parameter_selection)

    def test_paired_publication_dispatch_does_not_launch_unrelated_matrices(self) -> None:
        self.assertIn("- publication-nonregression", self.workflow)
        generic = self.section("  investigate:\n", "  repository-aging-smoke:\n")
        self.assertIn("inputs.profile != 'publication-nonregression'", generic)
        threshold = self.section("  stateless-threshold:\n", "  publication-nonregression:\n")
        self.assertIn("inputs.profile != 'publication-nonregression'", threshold)

    def test_paired_publication_native_results_are_validated_before_upload(self) -> None:
        self.assertIn("  publication-nonregression:\n", self.workflow)
        paired = self.workflow.partition("  publication-nonregression:\n")[2]
        self.assertIn("github.event_name == 'workflow_dispatch'", paired)
        self.assertIn("inputs.profile == 'publication-nonregression'", paired)
        self.assertIn("timeout-minutes: 150", paired)
        self.assertIn("-Dtest=PackPublicationPairedBenchmarkIT", paired)
        self.assertIn("summarize-publication-nonregression.py", paired)
        self.assertIn("validate_search_database_telemetry.py --privacy-only", paired)
        self.assertLess(paired.index("summarize-publication-nonregression.py"), paired.index("actions/upload-artifact@"))
        self.assertNotIn("jgit-storage-hibernate-benchmarks/target/**", paired)


if __name__ == "__main__":
    unittest.main()
