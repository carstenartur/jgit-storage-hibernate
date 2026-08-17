#!/usr/bin/env python3
"""Regression tests for non-snapshot commits in the snapshot workflow."""

from pathlib import Path
import unittest


WORKFLOW = Path(__file__).parents[1] / "workflows" / "publish-snapshot.yml"


class PublishSnapshotWorkflowTest(unittest.TestCase):
    def test_snapshot_version_enables_publication(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("id: snapshot", text)
        self.assertIn("echo \"publish=true\" >> \"$GITHUB_OUTPUT\"", text)

    def test_release_commit_is_skipped_without_marking_main_failed(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("echo \"publish=false\" >> \"$GITHUB_OUTPUT\"", text)
        self.assertIn(
            "Skipping snapshot publication for release version $VERSION", text
        )
        self.assertNotIn("Refusing to publish non-SNAPSHOT", text)
        self.assertNotIn("exit 1", text)

    def test_all_expensive_or_mutating_steps_are_snapshot_gated(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(
            3, text.count("if: steps.snapshot.outputs.publish == 'true'")
        )


if __name__ == "__main__":
    unittest.main()
