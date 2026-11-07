#!/usr/bin/env python3
"""Validate exact Search JMH invocation telemetry and retained artifact privacy."""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

WRITES = {"incrementalIndexing", "projectionRebuild"}
WAL_BYTES = "postgresql.wal.insert_lsn_bytes"
FORBIDDEN = (
    b"jgit.storage.benchmark.postgresql.url=",
    b"jgit.storage.benchmark.postgresql.user=",
    b"jgit.storage.benchmark.postgresql.password=",
    b"jdbc:postgresql://",
    b"Database JDBC URL",
    b"Default catalog/schema",
)


def scan_privacy(*roots: Path) -> None:
    for root in roots:
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            content = path.read_bytes()
            if any(token in content for token in FORBIDDEN):
                raise ValueError(f"Connection detail in retained Search artifact: {path}")


def _strict_json(text: str):
    def reject_constant(value: str):
        raise ValueError(f"Non-standard Search telemetry JSON constant: {value}")

    return json.loads(text, parse_constant=reject_constant)


def validate(root: Path, *additional_reports: Path) -> int:
    scan_privacy(root, *additional_reports)
    jmh = _strict_json((root / "jmh-result.json").read_text(encoding="utf-8"))
    expected = set()
    for row in jmh:
        params = row["params"]
        key = (
            row["benchmark"].rsplit(".", 1)[-1],
            params["indexProfile"],
            params["commitCount"],
            params["queryLimit"],
        )
        iterations = row["measurementIterations"]
        if type(iterations) is not int or iterations < 1:
            raise ValueError(f"No JMH measurements for Search coordinate: {key}")
        for invocation in range(1, iterations + 1):
            item = (*key, str(invocation))
            if item in expected:
                raise ValueError(f"Duplicate JMH Search coordinate: {item}")
            expected.add(item)

    aggregate = _strict_json(
        (root / "search-database-telemetry.json").read_text(encoding="utf-8")
    )
    if aggregate["schemaVersion"] != 1:
        raise ValueError("Unsupported Search telemetry schema")
    observations = aggregate["observations"]
    lines = (root / "search-database-telemetry.ndjson").read_text(encoding="utf-8").splitlines()
    if observations != [_strict_json(line) for line in lines]:
        raise ValueError("Search telemetry JSON and NDJSON differ")
    actual = set()
    for item in observations:
        coord = item["coordinate"]
        key = (
            coord["benchmarkMethod"],
            coord["indexProfile"],
            coord["commitCount"],
            coord["queryLimit"],
            coord["measurementInvocation"],
        )
        if key in actual:
            raise ValueError(f"Duplicate telemetry coordinate: {key}")
        actual.add(key)
        if item["backend"] != "postgresql" or item["enabled"] is not True:
            raise ValueError(f"Expected enabled PostgreSQL telemetry for {key}")
        if not item["serverVersion"]:
            raise ValueError(f"Missing PostgreSQL version for {key}")
        if datetime.fromisoformat(item["completedAt"]) < datetime.fromisoformat(item["startedAt"]):
            raise ValueError(f"Negative Search telemetry window for {key}")
        wal = item["counters"].get(WAL_BYTES)
        if key[0] in WRITES and (type(wal) is not int or wal <= 0):
            raise ValueError(f"Expected positive immediate WAL delta for {key}; got {wal!r}")

    if not expected or actual != expected:
        raise ValueError(
            "Search telemetry coordinates differ: "
            f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}"
        )
    return len(actual)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("additional_reports", nargs="*", type=Path)
    parser.add_argument("--privacy-only", action="store_true")
    args = parser.parse_args()
    if args.privacy_only:
        scan_privacy(args.artifacts, *args.additional_reports)
    else:
        count = validate(args.artifacts, *args.additional_reports)
        print(f"Validated {count} exact Search database telemetry observations")


if __name__ == "__main__":
    main()
