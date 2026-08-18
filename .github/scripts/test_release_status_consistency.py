#!/usr/bin/env python3
"""Regression tests for generated public-release status prose."""

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-release-consistency.py")
SPEC = importlib.util.spec_from_file_location("verify_release_consistency", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseStatusConsistencyTest(unittest.TestCase):
    def test_matching_generated_status_is_accepted(self) -> None:
        with self.fixture() as root:
            errors: list[str] = []
            previous = Path.cwd()
            os.chdir(root)
            try:
                MODULE.verify_release_status_prose(
                    "0.11.1-SNAPSHOT", "0.11.0", errors
                )
            finally:
                os.chdir(previous)
            self.assertEqual([], errors)

    def test_stale_current_upcoming_and_snapshot_claims_are_rejected(self) -> None:
        with self.fixture() as root:
            (root / "README.md").write_text(
                "The documented release line is **0.11.0**.\n"
                "The current public release is `0.10.0`.\n"
                "The upcoming `0.11.0` line is next.\n"
                "The `0.11.0-SNAPSHOT` development line is active.\n",
                encoding="utf-8",
            )
            errors: list[str] = []
            previous = Path.cwd()
            os.chdir(root)
            try:
                MODULE.verify_release_status_prose(
                    "0.11.1-SNAPSHOT", "0.11.0", errors
                )
            finally:
                os.chdir(previous)
            joined = "\n".join(errors)
            self.assertIn("current/latest public release or BOM", joined)
            self.assertIn("already released 0.11.0 as upcoming", joined)
            self.assertIn("stale snapshot reference 0.11.0-SNAPSHOT", joined)

    @staticmethod
    def fixture():
        class Fixture:
            def __enter__(self):
                self.directory = tempfile.TemporaryDirectory()
                root = Path(self.directory.name)
                for path in MODULE.RELEASE_STATUS_FILES:
                    destination = root / path
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_text(
                        "The documented release line is **0.11.0**.\n"
                        "The current public release is `0.11.0`.\n",
                        encoding="utf-8",
                    )
                return root

            def __exit__(self, exc_type, exc_value, traceback):
                self.directory.cleanup()

        return Fixture()


if __name__ == "__main__":
    unittest.main()
