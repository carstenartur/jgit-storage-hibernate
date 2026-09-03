#!/usr/bin/env python3
"""Contract checks for reviewed full restart-evidence dispatch requests."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = (
    ROOT / ".github/workflows/repository-aging-restart-evidence-request.yml"
)
TARGET_WORKFLOW = (
    ROOT / ".github/workflows/repository-aging-restart-evidence.yml"
)
VALIDATOR = (
    ROOT / ".github/scripts/validate_repository_aging_restart_request.py"
)
VALIDATOR_TEST = (
    ROOT / ".github/scripts/test_validate_repository_aging_restart_request.py"
)
FULL_SHA_ACTION = re.compile(r"uses:\s+([\w./-]+)@([0-9a-f]{40})(?:\s|$)")


class RestartEvidenceRequestWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.target_workflow = TARGET_WORKFLOW.read_text(encoding="utf-8")
        cls.validator = VALIDATOR.read_text(encoding="utf-8")
        cls.validator_test = VALIDATOR_TEST.read_text(encoding="utf-8")
        cls.contract_block, cls.dispatch_block = cls.workflow.split(
            "\n  dispatch:\n", 1
        )

    def test_events_separate_review_from_the_evidence_branch_request(self) -> None:
        for fragment in (
            "name: Dispatch Repository Aging Restart Evidence",
            "pull_request:",
            "branches: [ main ]",
            "- 'evidence/repository-aging-restart/**'",
            "- '.github/repository-aging-restart-evidence-request.json'",
            "if: github.event_name == 'pull_request'",
            "if: github.event_name == 'push'",
        ):
            self.assertIn(fragment, self.workflow)
        self.assertNotIn("pull_request_target:", self.workflow)
        self.assertEqual(
            1,
            self.workflow.count("evidence/repository-aging-restart/**"),
        )

    def test_write_permission_exists_only_on_the_push_dispatch_job(self) -> None:
        self.assertIn("permissions: {}", self.workflow)
        self.assertIn("permissions:\n      contents: read", self.contract_block)
        self.assertNotIn("actions: write", self.contract_block)
        self.assertIn("permissions:\n      actions: write\n      contents: read", self.dispatch_block)
        self.assertNotIn("contents: write", self.workflow)

    def test_request_is_bound_to_the_exact_current_main_commit(self) -> None:
        for fragment in (
            "fetch-depth: 0",
            "persist-credentials: false",
            "git fetch --no-tags origin",
            "refs/heads/main:refs/remotes/origin/main",
            'source_commit="$(git rev-parse refs/remotes/origin/main)"',
            "validate_repository_aging_restart_request.py",
            "--request .github/repository-aging-restart-evidence-request.json",
            '--expected-source-commit "$source_commit"',
            '--github-output "$GITHUB_OUTPUT"',
        ):
            self.assertIn(fragment, self.dispatch_block)
        for fragment in (
            "ALLOWED_KEYS",
            'request["enabled"] is not True',
            'source_commit != expected_source_commit',
            "Restart-evidence request is stale",
            "reason must be one line",
        ):
            self.assertIn(fragment, self.validator)
        for test_name in (
            "test_exact_current_main_request_is_normalized",
            "test_stale_main_commit_is_rejected",
            "test_disabled_and_extra_key_requests_are_rejected",
            "test_request_id_and_reason_are_single_line_and_bounded",
        ):
            self.assertIn(test_name, self.validator_test)

    def test_dispatch_targets_only_the_existing_main_workflow(self) -> None:
        for fragment in (
            "--request POST",
            "Accept: application/vnd.github+json",
            "Authorization: Bearer $GITHUB_TOKEN",
            "X-GitHub-Api-Version: 2022-11-28",
            "/actions/workflows/repository-aging-restart-evidence.yml/dispatches",
            "--data '{\"ref\":\"main\"}'",
            '[[ "$status" != 204 ]]',
            "Target workflow ref: `main`",
        ):
            self.assertIn(fragment, self.dispatch_block)
        self.assertIn("workflow_dispatch:", self.target_workflow)
        self.assertNotIn('--data \'{"ref":"$GITHUB_REF_NAME"}\'', self.workflow)

    def test_contract_executes_all_request_tests(self) -> None:
        for fragment in (
            "test_validate_repository_aging_restart_request.py",
            "test_repository_aging_restart_evidence_request_workflow.py",
            "python3 -m py_compile",
        ):
            self.assertIn(fragment, self.contract_block)

    def test_third_party_actions_are_pinned_to_full_commits(self) -> None:
        actions = FULL_SHA_ACTION.findall(self.workflow)
        self.assertEqual({"actions/checkout"}, {name for name, _ in actions})
        self.assertEqual(2, len(actions))
        self.assertNotRegex(self.workflow, r"uses:\s+[^\s]+@v[0-9]")


if __name__ == "__main__":
    unittest.main()
