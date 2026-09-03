#!/usr/bin/env python3
"""Contract checks for repeated provider-restart repository-aging evidence."""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/repository-aging-restart-evidence.yml"
NATIVE_WORKFLOW = ROOT / ".github/workflows/repository-aging-native-telemetry.yml"
BENCHMARK = (
    ROOT
    / "jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/"
    "jgit/storage/hibernate/benchmark/RepositoryAgingBenchmark.java"
)
RUNNER = (
    ROOT
    / "jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/"
    "jgit/storage/hibernate/benchmark/PerformanceInvestigationsBenchmarkIT.java"
)


class RepositoryAgingRestartEvidenceWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.native_workflow = NATIVE_WORKFLOW.read_text(encoding="utf-8")
        cls.benchmark = BENCHMARK.read_text(encoding="utf-8")
        cls.runner = RUNNER.read_text(encoding="utf-8")

    def test_workflow_has_bounded_pr_and_repeated_scheduled_matrices(self) -> None:
        for fragment in (
            "name: Repository Aging Restart Evidence",
            "workflow_dispatch:",
            "schedule:",
            "pull_request:",
            "push:",
            'regular = event not in {"schedule", "workflow_dispatch"}',
            'backends = ("postgresql", "sqlserver")',
            'cache_states = ("cold",) if regular else ("cold", "warm")',
            'repeats = ("1",) if regular else ("1", "2", "3")',
            "matrix=${matrix}",
            "matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}",
        ):
            self.assertIn(fragment, self.workflow)

    def test_workflow_selects_restart_without_mutating_existing_native_smoke(self) -> None:
        for fragment in (
            "-Djgit.storage.benchmark.repository-aging.native-smoke=true",
            '-Djgit.storage.benchmark.repository-aging.cache-state="$CACHE_STATE"',
            "-Djgit.storage.benchmark.repository-aging.provider-lifecycle=restarted-provider",
            '-Djgit.storage.benchmark.repository-aging.evidence-repeat="$EVIDENCE_REPEAT"',
            '-Djgit.storage.benchmark.deployment="${BACKEND}-restart-${CACHE_STATE}-repeat-${EVIDENCE_REPEAT}"',
        ):
            self.assertIn(fragment, self.workflow)
        self.assertNotIn(
            "repository-aging.provider-lifecycle", self.native_workflow
        )
        self.assertIn("Expected 27", self.native_workflow)

    def test_evidence_contract_requires_restart_phase_and_counter(self) -> None:
        for fragment in (
            "Expected 9",
            "Expected 36",
            "provider-restart",
            "providerLifecycle",
            "evidenceRepeat",
            "providerRestarts",
            "expected_phases = {",
            "'fixture-build'",
            "'maintenance'",
            "'provider-restart'",
            "'measurement'",
            "repository-aging-restart-${{ matrix.backend }}-${{ matrix.cache_state }}-repeat-${{ matrix.evidence_repeat }}",
        ):
            self.assertIn(fragment, self.workflow)

    def test_benchmark_uses_create_then_validate_for_restart(self) -> None:
        for fragment in (
            'static final String SAME_PROVIDER = "same-provider";',
            'static final String RESTARTED_PROVIDER = "restarted-provider";',
            "public String providerLifecycle;",
            "public int evidenceRepeat;",
            'properties(RESTARTED_PROVIDER.equals(providerLifecycle) ? "create" : "create-drop")',
            'captureSetupPhase("provider-restart", this::restartProvider);',
            'new HibernateSessionFactoryProvider(properties("validate"))',
            "providerRestartCount++;",
            "providerRestarts = benchmark.providerRestartCount;",
        ):
            self.assertIn(fragment, self.benchmark)
        self.assertLess(
            self.benchmark.index('captureSetupPhase("provider-restart"'),
            self.benchmark.index("verifyReachableFixture();"),
        )

    def test_runner_defaults_preserve_existing_measurements(self) -> None:
        for fragment in (
            "jgit.storage.benchmark.repository-aging.provider-lifecycle",
            "jgit.storage.benchmark.repository-aging.evidence-repeat",
            "new String[] {RepositoryAgingBenchmark.SAME_PROVIDER}",
            'new String[] {"1"}',
            '.param("providerLifecycle", providerLifecycles)',
            '.param("evidenceRepeat", evidenceRepeats)',
        ):
            self.assertIn(fragment, self.runner)


if __name__ == "__main__":
    unittest.main()
