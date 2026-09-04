#!/usr/bin/env python3
"""Contract tests for the reviewed full repository-aging dispatcher."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
REQUEST_WORKFLOW = (
    ROOT / ".github/workflows/repository-aging-full-evidence-request.yml"
)
TARGET_WORKFLOW = ROOT / ".github/workflows/performance-investigations.yml"
FULL_SHA_ACTION = re.compile(r"uses:\s+([^@\s]+)@([0-9a-f]{40})")


class FullAgingEvidenceRequestWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.request = REQUEST_WORKFLOW.read_text(encoding="utf-8")
        cls.target = TARGET_WORKFLOW.read_text(encoding="utf-8")

    def test_untrusted_pull_requests_have_no_write_permission(self) -> None:
        self.assertNotIn("pull_request_target:", self.request)
        self.assertIn("permissions: {}", self.request)
        self.assertIn("if: github.event_name == 'pull_request'", self.request)
        self.assertIn("contents: read", self.request)
        self.assertNotIn("contents: write", self.request)
        self.assertEqual(1, self.request.count("actions: write"))
        self.assertLess(
            self.request.index("if: github.event_name == 'push'"),
            self.request.index("actions: write"),
        )

    def test_request_pull_request_is_exactly_one_reviewed_file(self) -> None:
        for fragment in (
            "BASE_SHA: ${{ github.event.pull_request.base.sha }}",
            'git diff --name-only "$BASE_SHA"...HEAD',
            "request_path=.github/repository-aging-full-evidence-request.json",
            '"${#changed_paths[@]}" -ne 1',
            '"${changed_paths[0]}" != "$request_path"',
            "validate_repository_aging_full_request.py",
            '--expected-source-commit "$BASE_SHA"',
        ):
            self.assertIn(fragment, self.request)

    def test_push_job_revalidates_exact_protected_main_commit(self) -> None:
        for fragment in (
            "BEFORE_SHA: ${{ github.event.before }}",
            "AFTER_SHA: ${{ github.sha }}",
            'git diff --name-only "$BEFORE_SHA" "$AFTER_SHA"',
            '--expected-source-commit "$BEFORE_SHA"',
            'printf \'target_commit=%s\\n\' "$AFTER_SHA"',
            "Confirm main still resolves to the reviewed request commit",
            'refs/heads/main:refs/remotes/origin/main',
            '"$current_main" != "$GITHUB_SHA"',
        ):
            self.assertIn(fragment, self.request)

    def test_dispatch_requests_full_profile_and_correlates_new_exact_sha_run(self) -> None:
        for fragment in (
            "Dispatch and verify complete matrix on exact current main",
            "payload='{\"ref\":\"main\",\"inputs\":{\"profile\":\"full\"}}'",
            "actions/workflows/performance-investigations.yml/dispatches",
            "head_sha=$TARGET_COMMIT",
            'run.get("event") == "workflow_dispatch"',
            'run.get("head_branch") == "main"',
            'run.get("head_sha") == target_commit',
            'run.get("id") not in existing',
            'existing_ids="$(mktemp)"',
            '"$runs_response" "$TARGET_COMMIT" > "$existing_ids"',
            "for attempt in $(seq 1 12)",
            'printf \'run_id=%s\\n\' "$run_id"',
            "No new full Performance Investigations run resolved to reviewed commit",
        ):
            self.assertIn(fragment, self.request)
        self.assertLess(
            self.request.index('> "$existing_ids" <<\'PY\''),
            self.request.index("--request POST"),
        )
        self.assertNotIn("expected_source_commit", self.request)
        self.assertNotIn("dispatch_started_at", self.request)

    def test_target_retains_complete_sharded_age_matrix(self) -> None:
        for fragment in (
            "repository-aging-full-shard:",
            "backend:\n          - hsqldb\n          - postgresql\n          - postgresql-hikari",
            "cache_state:\n          - cold\n          - warm",
            "jgit.storage.benchmark.investigation.profile=full",
            "repository-aging-full-summary:",
            "Expected six repository-aging shards",
            "convert-jmh-repository-aging.py",
        ):
            self.assertIn(fragment, self.target)

    def test_checkout_is_pinned_and_credentials_are_not_persisted(self) -> None:
        actions = FULL_SHA_ACTION.findall(self.request)
        self.assertEqual({"actions/checkout"}, {name for name, _ in actions})
        self.assertEqual(2, len(actions))
        self.assertNotRegex(self.request, r"uses:\s+[^\s]+@v[0-9]")
        self.assertEqual(2, self.request.count("persist-credentials: false"))
        self.assertIn("cancel-in-progress: false", self.request)


if __name__ == "__main__":
    unittest.main()
