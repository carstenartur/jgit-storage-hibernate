#!/usr/bin/env python3
"""Check that the small #230 comparison identifies executed, not merely resolved, Search."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).with_name("history_scroll_candidate_provenance.py")
ARTIFACTS = ("mapper-orm", "mapper-pojo-base", "engine", "util-common", "backend-lucene")
SCROLL_CLASS = "org.hibernate.search.backend.lucene.search.query.impl.LuceneSearchScrollImpl"


class CandidateProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.application = self.root / "application"
        self.source = self.root / "search"
        self.app_commit = self.git_fixture(self.application, "0.12.1-SNAPSHOT")
        self.source_commit = self.git_fixture(self.source, "8.4.2-SNAPSHOT")
        self.repo = self.root / "candidate-m2"
        self.source_record = self.root / "candidate-source.json"
        self.jars = self.install_jars("8.4.2-SNAPSHOT")

    @staticmethod
    def git_fixture(path, version):
        path.mkdir()
        (path / "pom.xml").write_text(f"<project><version>{version}</version></project>\n")
        subprocess.run(["git", "init", "-q", str(path)], check=True)
        subprocess.run(["git", "-C", str(path), "add", "pom.xml"], check=True)
        subprocess.run(["git", "-C", str(path), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "-qm", "Fixture"], check=True)
        return subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()

    def install_jars(self, version):
        jars = []
        for artifact in ARTIFACTS:
            name = "hibernate-search-" + artifact
            path = self.repo / "org/hibernate/search" / name / version / f"{name}-{version}.jar"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f"{name}:{version}".encode())
            jars.append(path)
        return jars

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *map(str, args)],
                              text=True, capture_output=True)

    def record_source(self, commit=None):
        return self.run_script("source", "--source-root", self.source, "--maven-repo", self.repo,
                               "--commit", commit or self.source_commit, "--version", "8.4.2-SNAPSHOT",
                               "--output", self.source_record)

    def runtime_fixture(self, version="8.4.2-SNAPSHOT"):
        output = self.root / ("candidate" if version.endswith("SNAPSHOT") else "base")
        reports = output / "surefire-reports"
        reports.mkdir(parents=True)
        jars = self.jars if version.endswith("SNAPSHOT") else self.install_jars(version)
        orm = self.root / "hibernate-core-7.4.14.Final.jar"
        orm.write_bytes(b"orm")
        classpath = os.pathsep.join(map(str, [*jars, orm]))
        (output / "runtime-classpath.txt").write_text(classpath + "\n")
        (output / "dependency-tree.txt").write_text("\n".join(
            f"org.hibernate.search:hibernate-search-{artifact}:jar:{version}:compile"
            for artifact in ARTIFACTS) + "\n")
        (output / "class-load-123.log").write_text(
            f"[2.100s][info][class,load] {SCROLL_CLASS} source: {jars[-1].as_uri()}\n")
        suites = {
            "HistoryScrollExportBenchmarkIT": "comparesScrollWithOffsetOnTheSelectedDatabase",
            "CommitSearchScrollingDatabaseIntegrationTest": "scrollingContractWorksOnPostgreSql",
            "CommitSearchScrollingH2Test": "transactionStartupFailureClosesSessionAndAllowsRetry",
        }
        for name, method in suites.items():
            suite = ET.Element("testsuite", name="io.github.carstenartur." + name,
                               tests="1", failures="0", errors="0", skipped="0")
            properties = ET.SubElement(suite, "properties")
            ET.SubElement(properties, "property", name="surefire.test.class.path", value=classpath)
            ET.SubElement(properties, "property", name="java.specification.version", value="21")
            ET.SubElement(suite, "testcase", name=method)
            ET.ElementTree(suite).write(reports / f"TEST-{name}.xml", encoding="utf-8")
        return output

    def record_runtime(self, output, version="8.4.2-SNAPSHOT"):
        args = ["runtime", "--application-root", self.application, "--artifacts", output,
                "--commit", self.app_commit, "--version", version,
                "--variant", output.name, "--backend", "postgresql"]
        if output.name == "candidate":
            args += ["--source-manifest", self.source_record]
        return self.run_script(*args)

    def assert_recorded_failure(self, process, output):
        self.assertNotEqual(0, process.returncode, process.stdout)
        self.assertTrue(output.is_file(), "Failure must preserve a provenance record")
        self.assertEqual("failed", json.loads(output.read_text())["status"])

    def test_candidate_records_exact_source_and_executed_search_hashes(self):
        process = self.record_source()
        self.assertEqual(0, process.returncode, process.stderr)
        source = json.loads(self.source_record.read_text())
        self.assertEqual(self.source_commit, source["sourceCommit"])
        expected_tree = subprocess.check_output(
            ["git", "-C", str(self.source), "rev-parse", "HEAD^{tree}"], text=True).strip()
        self.assertEqual(expected_tree, source["sourceTree"])
        output = self.runtime_fixture()
        process = self.record_runtime(output)
        self.assertEqual(0, process.returncode, process.stderr)
        recorded = json.loads((output / "provenance.json").read_text())
        self.assertEqual(self.app_commit, recorded["applicationCommit"])
        self.assertEqual("validated", recorded["status"])
        backend = "hibernate-search-backend-lucene-8.4.2-SNAPSHOT.jar"
        self.assertEqual(hashlib.sha256(b"hibernate-search-backend-lucene:8.4.2-SNAPSHOT").hexdigest(),
                         recorded["searchJars"][backend]["sha256"])

    def test_release_needs_no_candidate_source_manifest(self):
        output = self.runtime_fixture("8.4.1.Final")
        process = self.record_runtime(output, "8.4.1.Final")
        self.assertEqual(0, process.returncode, process.stderr)

    def test_wrong_source_commit_preserves_failed_record(self):
        process = self.record_source("0" * 40)
        self.assert_recorded_failure(process, self.source_record)

    def test_matching_version_and_dependency_tree_do_not_replace_classload_evidence(self):
        self.assertEqual(0, self.record_source().returncode)
        output = self.runtime_fixture()
        other = self.root / self.jars[-1].name
        other.write_bytes(self.jars[-1].read_bytes())
        (output / "class-load-123.log").write_text(
            f"[2.100s][info][class,load] {SCROLL_CLASS} source: {other.as_uri()}\n")
        self.assert_recorded_failure(self.record_runtime(output), output / "provenance.json")

    def test_candidate_jar_changed_after_source_build_is_rejected(self):
        self.assertEqual(0, self.record_source().returncode)
        output = self.runtime_fixture()
        self.jars[-1].write_bytes(b"another build with the same version")
        self.assert_recorded_failure(self.record_runtime(output), output / "provenance.json")

    def test_skipped_native_contract_is_not_success(self):
        self.assertEqual(0, self.record_source().returncode)
        output = self.runtime_fixture()
        path = output / "surefire-reports/TEST-CommitSearchScrollingDatabaseIntegrationTest.xml"
        tree = ET.parse(path)
        tree.getroot().set("skipped", "1")
        ET.SubElement(tree.getroot().find("testcase"), "skipped")
        tree.write(path)
        self.assert_recorded_failure(self.record_runtime(output), output / "provenance.json")

    def test_missing_reports_leave_partial_provenance(self):
        self.assertEqual(0, self.record_source().returncode)
        output = self.runtime_fixture()
        (output / "surefire-reports/TEST-HistoryScrollExportBenchmarkIT.xml").unlink()
        self.assert_recorded_failure(self.record_runtime(output), output / "provenance.json")


if __name__ == "__main__":
    unittest.main()
