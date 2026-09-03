#!/usr/bin/env python3
"""Contract checks for repeated provider-restart repository-aging evidence."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/repository-aging-restart-evidence.yml"
NATIVE_WORKFLOW = ROOT / ".github/workflows/repository-aging-native-telemetry.yml"
EVIDENCE_TOOL = ROOT / ".github/scripts/repository_aging_restart_evidence.py"
EVIDENCE_TOOL_TEST = (
    ROOT / ".github/scripts/test_repository_aging_restart_evidence.py"
)
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
FULL_SHA_ACTION = re.compile(r"uses:\s+([\w./-]+)@([0-9a-f]{40})(?:\s|$)")


class RepositoryAgingRestartEvidenceWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.native_workflow = NATIVE_WORKFLOW.read_text(encoding="utf-8")
        cls.evidence_tool = EVIDENCE_TOOL.read_text(encoding="utf-8")
        cls.evidence_tool_test = EVIDENCE_TOOL_TEST.read_text(encoding="utf-8")
        cls.benchmark = BENCHMARK.read_text(encoding="utf-8")
        cls.runner = RUNNER.read_text(encoding="utf-8")

    def test_workflow_has_bounded_and_controlled_repeated_matrices(self) -> None:
        for fragment in (
            "name: Repository Aging Restart Evidence",
            "workflow_dispatch:",
            "schedule:",
            "pull_request:",
            "push:",
            "- 'evidence/repository-aging-restart/**'",
            'ref_name = os.environ["GITHUB_REF_NAME"]',
            'evidence_branch = ref_name.startswith(',
            '"evidence/repository-aging-restart/"',
            'event not in {"schedule", "workflow_dispatch"}',
            "and not evidence_branch",
            'backends = ("postgresql", "sqlserver")',
            'cache_states = ("cold",) if regular else ("cold", "warm")',
            'repeats = ("1",) if regular else ("1", "2", "3")',
            "matrix=${matrix}",
            "matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}",
        ):
            self.assertIn(fragment, self.workflow)
        self.assertLess(
            len(self.workflow.splitlines()),
            315,
            "Keep evidence validation in the tested Python tool, not inline YAML",
        )

    def test_only_explicit_evidence_branches_expand_push_runs(self) -> None:
        self.assertIn("Plan bounded or repeated evidence matrix", self.workflow)
        self.assertIn("Select bounded or repeated evidence coordinates", self.workflow)
        self.assertEqual(
            1,
            self.workflow.count("evidence/repository-aging-restart/**"),
        )
        self.assertNotIn("refs/heads/evidence/", self.workflow)
        self.assertIn("and not evidence_branch", self.workflow)

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

    def test_workflow_delegates_validation_and_aggregation_to_tested_tool(self) -> None:
        for fragment in (
            "repository_aging_restart_evidence.py validate",
            '--failsafe-root jgit-storage-hibernate-benchmarks/target/failsafe-reports',
            "repository_aging_restart_evidence.py aggregate",
            "repository-aging-restart-reproducibility.md",
            "repository-aging-restart-${{ matrix.backend }}-${{ matrix.cache_state }}-repeat-${{ matrix.evidence_repeat }}",
            "9 exact JMH coordinates and 36 phase observations validated",
        ):
            self.assertIn(fragment, self.workflow)
        for fragment in (
            "test_repository_aging_restart_evidence.py",
            "test_repository_aging_restart_evidence_workflow.py",
            "-Dtest=RepositoryAgingBenchmarkTest",
        ):
            self.assertIn(fragment, self.workflow)

    def test_evidence_tool_requires_restart_phase_counter_and_repeats(self) -> None:
        for fragment in (
            '"provider-restart"',
            '"providerLifecycle": "restarted-provider"',
            '"evidenceRepeat"',
            'name == "providerRestarts"',
            'name.endswith(".providerRestarts")',
            "Expected 9 JMH results",
            "Expected 36 phase observations",
            'expected_files = 2 if regular else 12',
            'expected_repeats = {"1"} if regular else {"1", "2", "3"}',
            '"automaticMaintenanceChanged": False',
            "coefficientOfVariationPercent",
        ):
            self.assertIn(fragment, self.evidence_tool)
        for fragment in (
            "test_exact_restart_evidence_is_accepted",
            "test_missing_provider_restart_phase_is_rejected",
            "test_provider_restart_counter_must_be_exactly_one",
            "test_full_matrix_is_aggregated_into_repeat_dispersion",
        ):
            self.assertIn(fragment, self.evidence_tool_test)

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

    def test_third_party_actions_are_pinned_to_full_commits(self) -> None:
        actions = FULL_SHA_ACTION.findall(self.workflow)
        self.assertEqual(
            {
                "actions/checkout",
                "actions/setup-java",
                "actions/upload-artifact",
                "actions/download-artifact",
            },
            {name for name, _ in actions},
        )
        self.assertNotRegex(self.workflow, r"uses:\s+[^\s]+@v[0-9]")


if __name__ == "__main__":
    unittest.main()
