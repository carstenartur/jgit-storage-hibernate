#!/usr/bin/env python3
"""A counterfactual permits exactly the reviewed reftable change."""

import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "publication_diagnostic_source", Path(__file__).with_name("verify-publication-diagnostic-source.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublicationDiagnosticSourceTest(unittest.TestCase):
    def test_original_variant_requires_no_guarded_change(self):
        MODULE.validate_difference("original", [], None)
        with self.assertRaisesRegex(ValueError, "unchanged"):
            MODULE.validate_difference("original", [MODULE.REFTABLE_PATH], MODULE.REFTABLE_BLOB)

    def test_counterfactual_requires_exact_reviewed_file_and_blob(self):
        MODULE.validate_difference("reftable", [MODULE.REFTABLE_PATH], MODULE.REFTABLE_BLOB)
        for paths, blob in (([], MODULE.REFTABLE_BLOB),
                            ([MODULE.REFTABLE_PATH], "0" * 40),
                            ([MODULE.REFTABLE_PATH, "pom.xml"], MODULE.REFTABLE_BLOB)):
            with self.subTest(paths=paths, blob=blob):
                with self.assertRaisesRegex(ValueError, "reviewed"):
                    MODULE.validate_difference("reftable", paths, blob)

    def test_unknown_variant_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "variant"):
            MODULE.validate_difference("higher-heap", [], None)


class CommittedDiagnosticSourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        previous = Path.cwd()
        os.chdir(self.directory.name)
        self.addCleanup(os.chdir, previous)
        self.source = Path("core/Library.java")
        self.source.parent.mkdir()
        self.source.write_text("original\n")
        self.git("init", "-q")
        self.git("add", "core")
        self.git("commit", "-qm", "Original source")
        baseline = self.git("rev-parse", "HEAD").strip()
        self.source.write_text("reviewed correction\n")
        blob = self.git("hash-object", str(self.source)).strip()
        self.overrides = patch.multiple(MODULE, BASELINE=baseline,
                                       REFTABLE_PATH=str(self.source), REFTABLE_BLOB=blob,
                                       GUARDED_PATHS=["core"])
        self.overrides.start()
        self.addCleanup(self.overrides.stop)

    def git(self, *args):
        return subprocess.check_output(["git", "-c", "user.name=Diagnostic test",
                                        "-c", "user.email=diagnostic@example.invalid", *args], text=True)

    def test_uncommitted_reviewed_change_cannot_claim_original_tree(self):
        with self.assertRaisesRegex(ValueError, "committed"):
            MODULE.verify("reftable", Path("inventory.txt"))
        self.assertFalse(Path("inventory.txt").exists())

    def test_untracked_measured_source_cannot_escape_inventory(self):
        self.git("add", "core")
        self.git("commit", "-qm", "Reviewed correction")
        Path("core/Untracked.java").write_text("untracked source\n")
        with self.assertRaisesRegex(ValueError, "untracked"):
            MODULE.verify("reftable", Path("inventory.txt"))
        self.assertFalse(Path("inventory.txt").exists())

    def test_committed_reviewed_source_has_matching_inventory(self):
        self.git("add", "core")
        self.git("commit", "-qm", "Reviewed correction")
        MODULE.verify("reftable", Path("inventory.txt"))
        self.assertIn(MODULE.REFTABLE_BLOB, Path("inventory.txt").read_text())


if __name__ == "__main__":
    unittest.main()
