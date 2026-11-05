#!/usr/bin/env python3
"""Exercise the database qualification gate with actual Surefire XML files."""

from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).with_name("verify-database-test-evidence.py")
RELEASE = Path(__file__).with_name("release.sh")
CORE = "io.github.carstenartur.jgit.storage.hibernate."
EXPECTED = (
    ("core", CORE + "CoreOracleSchemaMigrationIntegrationTest"),
    ("core", CORE + "RepositoryTransferOracleDatabaseIntegrationTest"),
    ("core", CORE + "PackRepackMaintenanceConcurrencyOracleIntegrationTest"),
    ("search", CORE + "search.SearchOracleSchemaMigrationIntegrationTest"),
    ("search", CORE + "search.SearchOracleQueryIntegrationTest"),
    ("security", CORE + "security.SecurityOracleSchemaMigrationIntegrationTest"),
    ("spring-boot-autoconfigure", CORE + "spring.autoconfigure.OracleAutoConfigurationIntegrationTest"),
    ("core", CORE + "CoreSqlServerSchemaMigrationIntegrationTest"),
    ("core", CORE + "RepositoryTransferSqlServerDatabaseIntegrationTest"),
    ("core", CORE + "PackRepackMaintenanceConcurrencySqlServerIntegrationTest"),
    ("search", CORE + "search.SearchSqlServerSchemaMigrationIntegrationTest"),
    ("search", CORE + "search.SearchSqlServerQueryIntegrationTest"),
    ("security", CORE + "security.SecuritySqlServerSchemaMigrationIntegrationTest"),
)


class DatabaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = []
        for module, classname in EXPECTED:
            path = self.root / f"jgit-storage-hibernate-{module}" / "target/surefire-reports" / f"TEST-{classname}.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            suite = ET.Element("testsuite", name=classname, tests="1", failures="0", errors="0", skipped="0")
            ET.SubElement(suite, "testcase", name="qualifiesDatabase", classname=classname, time="0.5")
            ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
            self.reports.append(path)

    def gate(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root)], text=True, capture_output=True)

    def modify(self, change):
        report = self.reports[0]
        tree = ET.parse(report)
        change(tree.getroot())
        tree.write(report, encoding="utf-8", xml_declaration=True)

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertIn(self.reports[0].name, result.stderr)

    def test_success_requires_all_expected_database_reports(self):
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("13", result.stdout)

    def test_missing_report_cannot_qualify_database(self):
        self.reports[0].unlink()
        self.assert_rejected(self.gate())

    def test_each_required_report_is_checked(self):
        for report in self.reports:
            with self.subTest(report=report.name):
                content = report.read_bytes()
                report.unlink()
                result = self.gate()
                report.write_bytes(content)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(report.name, result.stderr)

    def test_zero_tests_cannot_qualify_database(self):
        self.modify(lambda suite: suite.set("tests", "0"))
        self.assert_rejected(self.gate())

    def test_failed_errored_or_skipped_suite_cannot_qualify_database(self):
        for counter in ("failures", "errors", "skipped"):
            with self.subTest(counter=counter):
                self.modify(lambda suite: suite.set(counter, "1"))
                self.assert_rejected(self.gate())
                self.modify(lambda suite: suite.set(counter, "0"))

    def test_testcase_failure_error_or_skip_is_rejected_even_with_zero_counters(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.modify(lambda suite: ET.SubElement(suite.find("testcase"), tag))
                self.assert_rejected(self.gate())
                self.modify(lambda suite: suite.find("testcase").remove(suite.find(f"testcase/{tag}")))

    def test_positive_counter_without_testcases_is_incomplete(self):
        self.modify(lambda suite: suite.remove(suite.find("testcase")))
        self.assert_rejected(self.gate())

    def test_counter_must_match_number_of_testcases(self):
        self.modify(lambda suite: suite.set("tests", "2"))
        self.assert_rejected(self.gate())

    def test_malformed_xml_is_rejected(self):
        self.reports[0].write_text("<testsuite", encoding="utf-8")
        self.assert_rejected(self.gate())

    def test_missing_or_invalid_counters_are_rejected(self):
        for value in (None, "not-a-number", "-1"):
            with self.subTest(value=value):
                self.modify(lambda suite: suite.attrib.pop("errors", None) if value is None else suite.set("errors", value))
                self.assert_rejected(self.gate())

    def test_report_must_belong_to_required_suite(self):
        self.modify(lambda suite: suite.set("name", "unrelated.Test"))
        self.assert_rejected(self.gate())

    def test_testcases_must_belong_to_required_class(self):
        self.modify(lambda suite: suite.find("testcase").set("classname", "unrelated.Test"))
        self.assert_rejected(self.gate())

    def run_release_build(self, skip_tests):
        scripts = self.root / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copyfile(SCRIPT, scripts / SCRIPT.name)
        for command in ("mvn", "docker"):
            executable = self.root / command
            executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            executable.chmod(0o755)
        function = re.search(r"(?ms)^run_complete_build\(\) \{.*?^\}", RELEASE.read_text()).group()
        import os
        env = dict(os.environ, SKIP_TESTS=skip_tests, PATH=f"{self.root}:{os.environ['PATH']}")
        return subprocess.run(["bash", "-ec", function + "\nrun_complete_build"], cwd=self.root, env=env, text=True, capture_output=True)

    def test_release_rejects_missing_database_evidence_after_successful_maven(self):
        self.reports[0].unlink()
        self.assert_rejected(self.run_release_build("false"))

    def test_release_accepts_complete_successful_evidence(self):
        result = self.run_release_build("false")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_skip_test_dry_run_does_not_require_database_evidence(self):
        self.reports[0].unlink()
        result = self.run_release_build("true")
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
