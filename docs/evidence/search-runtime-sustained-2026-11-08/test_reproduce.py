#!/usr/bin/env python3
"""Regression checks for archive completeness and safe, unchanged replay."""

import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("sustained_replay", ROOT / "reproduce.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReplayTest(unittest.TestCase):
    def test_missing_or_duplicate_coordinate_is_not_a_complete_run(self):
        original = json.loads((ROOT / "provenance.json").read_text())
        MODULE.validate_provenance(original)
        for duplicate in (False, True):
            changed = copy.deepcopy(original)
            changed["artifacts"].pop()
            if duplicate:
                changed["artifacts"].append(copy.deepcopy(changed["artifacts"][0]))
            with self.subTest(duplicate=duplicate), self.assertRaisesRegex(ValueError, "four|coordinate"):
                MODULE.validate_provenance(changed)

    def test_corrupt_archive_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "evidence.zip"
            expected = hashlib.sha256(b"original archive").hexdigest()
            path.write_bytes(b"changed archive")
            with self.assertRaisesRegex(ValueError, "digest"):
                MODULE.extract_verified_archive(path, expected, Path(temporary) / "output")

    def test_verified_zip_cannot_extract_outside_its_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "evidence.zip"
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as archive:
                archive.writestr("../escape.txt", "unexpected")
            path.write_bytes(data.getvalue())
            with self.assertRaisesRegex(ValueError, "path"):
                MODULE.extract_verified_archive(path, hashlib.sha256(data.getvalue()).hexdigest(),
                                                Path(temporary) / "output")
            self.assertFalse((Path(temporary) / "escape.txt").exists())

    def test_junit_failure_element_is_not_hidden_by_zero_summary_counter(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "jgit-storage-hibernate-benchmarks/target/surefire-reports"
            reports.mkdir(parents=True)
            for name, count in MODULE.EXPECTED_TESTS.items():
                cases = "<testcase><failure/></testcase>" + "<testcase/>" * (count - 1)
                (reports / f"TEST-{name}.xml").write_text(
                    f'<testsuite name="{name}" tests="{count}" failures="0" errors="0" skipped="0">'
                    + cases + "</testsuite>")
            with self.assertRaisesRegex(ValueError, "JUnit gate failed"):
                MODULE.validate_junit(root)


if __name__ == "__main__":
    unittest.main()
