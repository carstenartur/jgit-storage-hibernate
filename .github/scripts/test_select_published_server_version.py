#!/usr/bin/env python3
"""Regression tests for selecting the already published server image."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("select_published_server_version.py")
SPEC = importlib.util.spec_from_file_location("select_published_server_version", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
SELECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SELECTOR)


class PublishedServerVersionSelectorTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        self.run("git", "init", "-q")
        self.run("git", "config", "user.name", "Test")
        self.run("git", "config", "user.email", "test@example.invalid")

        current_release = self.repository / "docs/current-release-version.txt"
        current_release.parent.mkdir(parents=True)
        current_release.write_text("0.11.2\n", encoding="utf-8")
        self.run("git", "add", ".")
        self.run("git", "commit", "-q", "-m", "published release")
        self.source_commit = self.output("git", "rev-parse", "HEAD")

        current_release.write_text("0.11.3\n", encoding="utf-8")
        candidate_path = self.repository / ".github/release-candidate.json"
        candidate_path.parent.mkdir(parents=True)
        candidate_path.write_text(
            json.dumps(
                {
                    "release_version": "0.11.3",
                    "next_development_version": "0.11.4-SNAPSHOT",
                    "source_commit": self.source_commit,
                }
            )
            + "\n",
            encoding="utf-8",
        )
        self.run("git", "add", ".")
        self.run("git", "commit", "-q", "-m", "release candidate")

    def test_release_candidate_dispatch_uses_recorded_source_release(self) -> None:
        self.assertEqual(
            "0.11.2",
            SELECTOR.select_version(
                self.repository,
                "workflow_dispatch",
                "release/prepare-0.11.3",
            ),
        )

    def test_pull_request_uses_base_commit_release(self) -> None:
        self.assertEqual(
            "0.11.2",
            SELECTOR.select_version(
                self.repository,
                "pull_request",
                "332/merge",
                self.source_commit,
            ),
        )

    def test_main_and_next_development_dispatch_use_checked_out_release(self) -> None:
        for ref_name in ("main", "release/next-0.11.4"):
            with self.subTest(ref_name=ref_name):
                self.assertEqual(
                    "0.11.3",
                    SELECTOR.select_version(
                        self.repository,
                        "workflow_dispatch",
                        ref_name,
                    ),
                )

    def test_release_candidate_dispatch_requires_candidate_metadata(self) -> None:
        (self.repository / ".github/release-candidate.json").unlink()
        with self.assertRaisesRegex(
            ValueError, "requires .github/release-candidate.json"
        ):
            SELECTOR.select_version(
                self.repository,
                "workflow_dispatch",
                "release/prepare-0.11.3",
            )

    def test_invalid_release_version_is_rejected(self) -> None:
        (self.repository / "docs/current-release-version.txt").write_text(
            "0.11.3-SNAPSHOT\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "must match X.Y.Z"):
            SELECTOR.select_version(
                self.repository,
                "workflow_dispatch",
                "main",
            )

    def run(self, *command: str) -> None:
        subprocess.run(command, cwd=self.repository, check=True)

    def output(self, *command: str) -> str:
        return subprocess.run(
            command,
            cwd=self.repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()


if __name__ == "__main__":
    unittest.main()
