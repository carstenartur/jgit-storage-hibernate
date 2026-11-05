#!/usr/bin/env python3
"""Require retained database test evidence after the ordinary Maven build."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


PACKAGE = "io.github.carstenartur.jgit.storage.hibernate"
REQUIRED_SUITES = {
    "core": (
        f"{PACKAGE}.CoreOracleSchemaMigrationIntegrationTest",
        f"{PACKAGE}.RepositoryTransferOracleDatabaseIntegrationTest",
        f"{PACKAGE}.PackRepackMaintenanceConcurrencyOracleIntegrationTest",
        f"{PACKAGE}.CoreSqlServerSchemaMigrationIntegrationTest",
        f"{PACKAGE}.RepositoryTransferSqlServerDatabaseIntegrationTest",
        f"{PACKAGE}.PackRepackMaintenanceConcurrencySqlServerIntegrationTest",
    ),
    "search": (
        f"{PACKAGE}.search.SearchOracleSchemaMigrationIntegrationTest",
        f"{PACKAGE}.search.SearchOracleQueryIntegrationTest",
        f"{PACKAGE}.search.SearchSqlServerSchemaMigrationIntegrationTest",
        f"{PACKAGE}.search.SearchSqlServerQueryIntegrationTest",
    ),
    "security": (
        f"{PACKAGE}.security.SecurityOracleSchemaMigrationIntegrationTest",
        f"{PACKAGE}.security.SecuritySqlServerSchemaMigrationIntegrationTest",
    ),
    "spring-boot-autoconfigure": (
        f"{PACKAGE}.spring.autoconfigure.OracleAutoConfigurationIntegrationTest",
    ),
}


def verify_report(path, classname):
    """Reject absent, incomplete or unsuccessful Surefire evidence."""
    try:
        suite = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"cannot read Surefire report: {error}") from error
    if suite.tag != "testsuite" or suite.get("name") != classname:
        raise ValueError(f"expected testsuite for {classname}")
    try:
        counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    except (KeyError, ValueError) as error:
        raise ValueError("missing or invalid tests/failures/errors/skipped counters") from error
    if counts["tests"] <= 0 or any(counts[key] != 0 for key in ("failures", "errors", "skipped")):
        raise ValueError(f"must execute tests with no failures, errors or skips: {counts}")
    cases = suite.findall("testcase")
    if len(cases) != counts["tests"]:
        raise ValueError(f"incomplete evidence: tests={counts['tests']}, testcase rows={len(cases)}")
    for case in cases:
        if case.get("classname") != classname or not case.get("name"):
            raise ValueError("testcase is missing its name or belongs to a different class")
        if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise ValueError(f"unsuccessful testcase: {case.get('name')}")
    return counts["tests"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="Maven reactor root (default: working directory)")
    args = parser.parse_args()
    reports = tests = 0
    errors = []
    for module, classes in REQUIRED_SUITES.items():
        for classname in classes:
            report = args.root / f"jgit-storage-hibernate-{module}" / "target/surefire-reports" / f"TEST-{classname}.xml"
            try:
                tests += verify_report(report, classname)
                reports += 1
            except ValueError as error:
                errors.append(f"{report}: {error}")
    if errors:
        print("Database qualification evidence rejected:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    print(f"Database qualification evidence accepted: {reports} Oracle/SQL Server suites, {tests} tests, no skips/failures/errors.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
