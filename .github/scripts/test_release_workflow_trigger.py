#!/usr/bin/env python3
"""Regression tests for the repository-owned release workflow trigger."""

from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).parents[1] / "workflows" / "release.yml"
RELEASE_SCRIPT = Path(__file__).with_name("release.sh")


class ReleaseWorkflowTriggerTest(unittest.TestCase):
    """Keep release preparation and publication behind protected pull requests."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.script = RELEASE_SCRIPT.read_text(encoding="utf-8")
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
        self.assertIn("startsWith(github.event.pull_request.head.ref, 'release/prepare-')", self.text)

    def test_workflow_delegates_both_release_phases(self) -> None:
        self.assertIn("RELEASE_ACTION: prepare", self.text)
        self.assertIn("RELEASE_ACTION: finalize", self.text)
        self.assertIn("prepare_release", self.script)
        self.assertIn("finalize_release", self.script)

    def test_release_script_never_pushes_directly_to_main(self) -> None:
        self.assertNotIn("HEAD:main", self.script)
        self.assertNotIn("HEAD:refs/heads/main", self.script)


if __name__ == "__main__":
    unittest.main()
