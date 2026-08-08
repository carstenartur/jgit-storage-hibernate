#!/usr/bin/env python3
"""Regression tests for the repository-owned release workflow trigger."""

from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).parents[1] / "workflows" / "release.yml"


class ReleaseWorkflowTriggerTest(unittest.TestCase):
    """Keep branch creation from starting a marker-less release attempt."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
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


if __name__ == "__main__":
    unittest.main()
