#!/usr/bin/env python3
"""Keep CI status separate from generated badge publication side effects."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
CI = ROOT / ".github/workflows/maven.yml"
PUBLISH = ROOT / ".github/workflows/publish-build-badges.yml"
README = ROOT / "README.md"
FULL_SHA_ACTION = re.compile(
    r"uses:\s+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([0-9a-f]{40})(?:\s+#.*)?$",
    re.MULTILINE,
)
WRITE_ALL_PERMISSION = re.compile(
    r"^\s*permissions:\s*write-all\s*(?:#.*)?$", re.MULTILINE
)
NAMED_WRITE_PERMISSION = re.compile(
    r"^\s*[A-Za-z][A-Za-z0-9-]*:\s*write\s*(?:#.*)?$", re.MULTILINE
)
INLINE_WRITE_PERMISSION = re.compile(
    r"^\s*permissions:\s*\{[^}\n]*\bwrite\b[^}\n]*\}\s*(?:#.*)?$",
    re.MULTILINE,
)


class CiBadgeWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.ci = CI.read_text(encoding="utf-8")
        cls.publish = PUBLISH.read_text(encoding="utf-8")
        cls.readme = README.read_text(encoding="utf-8")

    def test_ci_workflow_contains_no_pages_publication_job(self) -> None:
        self.assertNotIn("publish-build-badges:", self.ci)
        self.assertNotIn("git push origin HEAD:gh-pages", self.ci)
        self.assertIn("name: Java CI with Maven", self.ci)
        self.assertIn("name: Maven verification", self.ci)
        self.assertIn("name: Public Maven repository contract", self.ci)

    def test_ci_workflow_cannot_acquire_write_permissions(self) -> None:
        self.assertIn("permissions:\n  contents: read", self.ci)
        self.assertNotRegex(self.ci, WRITE_ALL_PERMISSION)
        self.assertNotRegex(self.ci, NAMED_WRITE_PERMISSION)
        self.assertNotRegex(self.ci, INLINE_WRITE_PERMISSION)

    def test_publication_runs_only_after_successful_main_ci(self) -> None:
        self.assertIn("name: Publish build badges", self.publish)
        self.assertIn("workflows: [ Java CI with Maven ]", self.publish)
        self.assertIn("types: [ completed ]", self.publish)
        self.assertIn("branches: [ main ]", self.publish)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", self.publish)
        self.assertIn("actions: read", self.publish)
        self.assertIn("contents: write", self.publish)
        self.assertNotRegex(self.publish, r"(?m)^\s+push:\s*$")

    def test_publication_downloads_the_exact_ci_artifact(self) -> None:
        for fragment in (
            "name: badge-metrics",
            "run-id: ${{ steps.source.outputs.run_id }}",
            "github-token: ${{ github.token }}",
            "repository: ${{ github.repository }}",
            'git -C "$pages_dir" push origin HEAD:gh-pages',
        ):
            self.assertIn(fragment, self.publish)

    def test_all_publication_actions_are_immutably_pinned(self) -> None:
        actions = FULL_SHA_ACTION.findall(self.publish)
        self.assertEqual(
            {"actions/checkout", "actions/download-artifact"},
            {name for name, _ in actions},
        )
        self.assertNotRegex(self.publish, r"uses:\s+[^\s]+@v[0-9]")

    def test_readme_ci_badge_targets_the_ci_workflow(self) -> None:
        self.assertIn(
            "actions/workflows/maven.yml/badge.svg",
            self.readme,
        )
        self.assertNotIn(
            "actions/workflows/publish-build-badges.yml/badge.svg",
            self.readme,
        )


if __name__ == "__main__":
    unittest.main()
