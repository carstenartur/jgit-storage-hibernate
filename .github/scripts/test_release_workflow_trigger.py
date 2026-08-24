#!/usr/bin/env python3
"""Regression tests for the repository-owned release workflow trigger."""

from pathlib import Path
import re
import subprocess
import sys
import unittest


WORKFLOW = Path(__file__).parents[1] / "workflows" / "release.yml"
RELEASE_SCRIPT = Path(__file__).with_name("release.sh")
DISPATCH_SCRIPT = Path(__file__).with_name("dispatch-generated-pr-checks.sh")
RECOVERY_REGRESSION_TESTS = (
    Path(__file__).with_name("test_recover_partial_release.py"),
    Path(__file__).with_name("test_publish_snapshot_workflow.py"),
    Path(__file__).with_name("test_release_status_consistency.py"),
)


class ReleaseWorkflowTriggerTest(unittest.TestCase):
    """Keep release preparation and publication behind protected pull requests."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.script = RELEASE_SCRIPT.read_text(encoding="utf-8")
        cls.dispatch = DISPATCH_SCRIPT.read_text(encoding="utf-8")
        match = re.search(
            r"(?ms)^  push:\n(?P<body>.*?)(?=^\S|^permissions:)", cls.text
        )
        if match is None:
            raise AssertionError("release.yml has no push trigger")
        cls.push = match.group("body")

    def test_release_request_branch_is_required(self) -> None:
        self.assertIn("- 'release-request/**'", self.push)

    def test_only_the_repository_owned_marker_triggers_a_push_release(self) -> None:
        self.assertRegex(self.push, r"(?m)^    paths:\s*$")
        self.assertIn("- '.github/release-request'", self.push)

    def test_workflow_dispatch_remains_available(self) -> None:
        self.assertRegex(self.text, r"(?m)^  workflow_dispatch:\s*$")

    def test_release_workflow_keeps_one_global_serialization_group(self) -> None:
        self.assertIn("group: jgit-storage-hibernate-release", self.text)
        self.assertIn("cancel-in-progress: false", self.text)

    def test_publication_requires_a_merged_release_pull_request(self) -> None:
        self.assertIn("types: [ closed ]", self.text)
        self.assertIn("github.event.pull_request.merged == true", self.text)
        self.assertIn(
            "startsWith(github.event.pull_request.head.ref, 'release/prepare-')",
            self.text,
        )

    def test_workflow_delegates_both_release_phases(self) -> None:
        self.assertIn("RELEASE_ACTION: prepare", self.text)
        self.assertIn("RELEASE_ACTION: finalize", self.text)
        self.assertIn("prepare_release", self.script)
        self.assertIn("finalize_release", self.script)

    def test_release_script_never_pushes_directly_to_main(self) -> None:
        self.assertNotIn("HEAD:main", self.script)
        self.assertNotIn("HEAD:refs/heads/main", self.script)

    def test_repository_token_replaces_missing_long_lived_pat(self) -> None:
        fallback = (
            "RELEASE_AUTOMATION_TOKEN: "
            "${{ secrets.RELEASE_GITHUB_TOKEN || github.token }}"
        )
        self.assertEqual(2, self.text.count(fallback))
        self.assertIn("actions: write", self.text)
        self.assertIn('export GH_TOKEN="$RELEASE_AUTOMATION_TOKEN"', self.script)

    def test_generated_release_and_next_pr_checks_are_dispatched(self) -> None:
        self.assertTrue(DISPATCH_SCRIPT.is_file())
        self.assertIn("Dispatch checks for generated release PR", self.text)
        self.assertIn("Dispatch checks for generated next-development PR", self.text)
        self.assertIn('"release/prepare-${RELEASE_VERSION}"', self.text)
        self.assertIn('"release/next-${NEXT_VERSION%-SNAPSHOT}"', self.text)
        self.assertIn('gh workflow run "$workflow" --ref "$branch"', self.dispatch)

    def test_release_preparation_asserts_pull_request_exists(self) -> None:
        self.assertIn("Expected a protected pull request", self.script)
        self.assertIn('gh pr view "$existing"', self.script)

    def test_recovery_regression_suites_are_executed(self) -> None:
        for test in RECOVERY_REGRESSION_TESTS:
            with self.subTest(test=test.name):
                self.assertTrue(test.is_file(), f"Missing recovery test {test}")
                subprocess.run([sys.executable, str(test)], check=True)


if __name__ == "__main__":
    unittest.main()
