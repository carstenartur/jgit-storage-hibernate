#!/usr/bin/env python3
"""Reject incomplete or contradictory JUnit evidence during offline replay."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("consumer_replay", Path(__file__).with_name("verify.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class JunitEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def report(self, cases, tests="1", failures="0", errors="0", skipped="0", name="example"):
        (self.root / f"TEST-{name}.xml").write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'
            + cases + "</testsuite>", encoding="utf-8")

    def test_counts_actual_successful_cases_across_reports(self):
        self.report('<testcase name="one"/>')
        self.report('<testcase name="two"/><testcase name="three"/>', tests="2", name="second")
        self.assertEqual(MODULE.junit(self.root), dict(tests=3, errors=0, failures=0, skipped=0))

    def test_hidden_failure_error_or_skip_is_not_qualified_by_zero_counters(self):
        for result in ("failure", "error", "skipped"):
            with self.subTest(result=result):
                self.report(f'<testcase name="one"><{result}/></testcase>')
                with self.assertRaisesRegex(ValueError, "unsuccessful"):
                    MODULE.junit(self.root)

    def test_declared_total_must_match_actual_testcase_count(self):
        for count in ("0", "2", "1347"):
            with self.subTest(count=count):
                self.report('<testcase name="one"/>', tests=count)
                with self.assertRaisesRegex(ValueError, "testcase count"):
                    MODULE.junit(self.root)

    def test_missing_testcases_do_not_count_as_executed(self):
        self.report("", tests="1")
        with self.assertRaisesRegex(ValueError, "testcase count"):
            MODULE.junit(self.root)

    def test_nonzero_summary_is_unsuccessful_even_without_failure_element(self):
        for counter in ("failures", "errors", "skipped"):
            with self.subTest(counter=counter):
                self.report('<testcase name="one"/>', **{counter: "1"})
                with self.assertRaisesRegex(ValueError, "unsuccessful"):
                    MODULE.junit(self.root)

    def test_invalid_or_missing_counters_are_rejected(self):
        for value in ("-1", "unknown"):
            with self.subTest(value=value):
                self.report('<testcase name="one"/>', tests=value)
                with self.assertRaises(ValueError):
                    MODULE.junit(self.root)
        (self.root / "TEST-example.xml").write_text('<testsuite><testcase/></testsuite>')
        with self.assertRaises(ValueError):
            MODULE.junit(self.root)

    def test_missing_reports_are_not_successful_zero_test_evidence(self):
        with self.assertRaisesRegex(ValueError, "No JUnit reports"):
            MODULE.junit(self.root)


if __name__ == "__main__":
    unittest.main()
