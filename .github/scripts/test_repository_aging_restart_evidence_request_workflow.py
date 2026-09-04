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

    def test_events_separate_review_from_protected_main_dispatch(self) -> None:
        for fragment in (
            "name: Dispatch Repository Aging Restart Evidence",
            "pull_request:",
            "branches: [ main ]",
            "push:\n    branches: [ main ]",
            "- '.github/repository-aging-restart-evidence-request.json'",
            "if: github.event_name == 'pull_request'",
            "if: github.event_name == 'push'",
        ):
            self.assertIn(fragment, self.workflow)
        self.assertNotIn("pull_request_target:", self.workflow)
        self.assertNotIn("evidence/repository-aging-restart/**", self.workflow)

    def test_write_permission_exists_only_on_protected_push_job(self) -> None:
        self.assertIn("permissions: {}", self.workflow)
        self.assertIn("permissions:\n      contents: read", self.contract_block)
        self.assertNotIn("actions: write", self.contract_block)
        self.assertIn(
            "permissions:\n      actions: write\n      contents: read",
            self.dispatch_block,
        )
        self.assertNotIn("contents: write", self.workflow)

    def test_request_pr_must_be_a_dedicated_one_file_change(self) -> None:
        for fragment in (
            "Checkout proposed contract or request",
            "ref: ${{ github.event.pull_request.head.sha }}",
            "fetch-depth: 0",
            "persist-credentials: false",
            "Validate a dedicated request change",
            "BASE_SHA: ${{ github.event.pull_request.base.sha }}",
            'git diff --name-only "$BASE_SHA"...HEAD',
            '[[ "${#changed_paths[@]}" -ne 1',
            '"${changed_paths[0]}" != "$request_path"',
            "A restart-evidence request PR may change only",
            '--expected-source-commit "$BASE_SHA"',
        ):
            self.assertIn(fragment, self.contract_block)

    def test_protected_request_commit_is_bound_to_before_and_after_shas(self) -> None:
        for fragment in (
            "Checkout exact protected request commit",
            "ref: ${{ github.sha }}",
            "BEFORE_SHA: ${{ github.event.before }}",
            "AFTER_SHA: ${{ github.sha }}",
            '"$BEFORE_SHA" == 0000000000000000000000000000000000000000',
            '[[ "$(git rev-parse HEAD)" != "$AFTER_SHA" ]]',
            'git diff --name-only "$BEFORE_SHA" "$AFTER_SHA"',
            "Protected request commit may change only",
            '--expected-source-commit "$BEFORE_SHA"',
            "target_commit=%s",
        ):
            self.assertIn(fragment, self.dispatch_block)

    def test_request_is_bound_to_the_exact_current_main_predecessor(self) -> None:
        for fragment in (
            "validate_repository_aging_restart_request.py",
            '--request "$request_path"',
            '--github-output "$GITHUB_OUTPUT"',
        ):
            self.assertIn(fragment, self.workflow)
        for fragment in (
            "ALLOWED_KEYS",
            'request["enabled"] is not True',
            'source_commit != expected_source_commit',
            "Restart-evidence request is stale",
            "reason must be one line",
            "request_id=",
            "source_commit=",
        ):
            self.assertIn(fragment, self.validator)
        for test_name in (
            "test_exact_current_main_request_is_normalized",
            "test_github_outputs_use_expression_safe_names",
            "test_stale_main_commit_is_rejected",
            "test_disabled_and_extra_key_requests_are_rejected",
            "test_request_id_and_reason_are_single_line_and_bounded",
        ):
            self.assertIn(test_name, self.validator_test)

    def test_expression_outputs_use_unambiguous_names(self) -> None:
        for output in ("request_id", "source_commit", "target_commit"):
            self.assertIn(f"steps.request.outputs.{output}", self.dispatch_block)
        for output in ("request-id", "source-commit", "target-commit"):
            self.assertNotIn(f"steps.request.outputs.{output}", self.workflow)

    def test_dispatch_refuses_when_main_advanced_after_request_merge(self) -> None:
        for fragment in (
            "Confirm main still resolves to the reviewed request commit",
            "refs/heads/main:refs/remotes/origin/main",
            'current_main="$(git rev-parse refs/remotes/origin/main)"',
            '[[ "$current_main" != "$GITHUB_SHA" ]]',
            "refusing a stale dispatch",
        ):
            self.assertIn(fragment, self.dispatch_block)

    def test_dispatch_targets_exact_current_main_in_existing_workflow(self) -> None:
        for fragment in (
            "Dispatch full restart evidence on exact current main",
            "TARGET_COMMIT: ${{ steps.request.outputs.target_commit }}",
            "--request POST",
            "Accept: application/vnd.github+json",
            "Content-Type: application/json",
            "Authorization: Bearer $GITHUB_TOKEN",
            "X-GitHub-Api-Version: 2022-11-28",
            "/actions/workflows/repository-aging-restart-evidence.yml/dispatches",
            "expected_source_commit",
            '"$TARGET_COMMIT"',
            '--data "$payload"',
            '[[ "$status" != 204 ]]',
            "Exact dispatched main commit",
        ):
            self.assertIn(fragment, self.dispatch_block)
        for fragment in (
            "expected_source_commit:",
            "Verify requested source commit",
            '[[ "$GITHUB_SHA" != "$EXPECTED_SOURCE_COMMIT" ]]',
        ):
            self.assertIn(fragment, self.target_workflow)

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
