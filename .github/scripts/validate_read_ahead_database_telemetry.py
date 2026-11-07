#!/usr/bin/env python3
"""Check read-ahead native telemetry against the exact JMH measurement coordinates."""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

from validate_search_database_telemetry import scan_privacy

POSTGRES_BACKENDS = {"postgresql", "postgresql-hikari"}
OPERATIONS = {"sequentialRead", "randomReads", "shortRead"}
WINDOWS = {"1", "4", "16"}
READ_COUNTERS = {"postgresql.database.blocks_read", "postgresql.database.blocks_hit"}


def strict_json(text: str):
    def reject(value: str):
        raise ValueError(f"Invalid telemetry JSON constant: {value}")

    return json.loads(text, parse_constant=reject)


def validate(root: Path, profile: str, *reports: Path) -> int:
    jmh = json.loads((root / "jmh-result.json").read_text(encoding="utf-8"))
    backends = {"postgresql"} if profile == "smoke" else POSTGRES_BACKENDS
    iterations = 1 if profile == "smoke" else 3
    matrix_backends = backends | {"hsqldb"} if profile == "full" else backends
    expected_rows = {
        (method, backend, window)
        for method in OPERATIONS
        for backend in matrix_backends
        for window in WINDOWS
    }
    rows = set()
    expected = set()
    for row in jmh:
        params = row["params"]
        key = (row["benchmark"].rsplit(".", 1)[-1], params["backend"], params["readAheadChunks"])
        if (
            key in rows
            or row["mode"] != "ss"
            or row["threads"] != 1
            or row["forks"] != 1
            or row["measurementIterations"] != iterations
            or row["measurementBatchSize"] != 1
        ):
            raise ValueError(f"Unexpected read-ahead JMH row: {key}")
        rows.add(key)
        if key[1] in POSTGRES_BACKENDS:
            expected.update((*key, str(i)) for i in range(1, iterations + 1))
    if rows != expected_rows:
        raise ValueError(f"Unexpected read-ahead JMH matrix: {sorted(rows ^ expected_rows)}")

    aggregate = strict_json((root / "read-ahead-database-telemetry.json").read_text(encoding="utf-8"))
    ndjson = (root / "read-ahead-database-telemetry.ndjson").read_text(encoding="utf-8").splitlines()
    observations = aggregate["observations"]
    if aggregate["schemaVersion"] != 1 or observations != [strict_json(line) for line in ndjson]:
        raise ValueError("Read-ahead telemetry JSON and NDJSON differ or use an unknown schema")
    actual = set()
    for item in observations:
        coord = item["coordinate"]
        key = tuple(
            coord[name]
            for name in ("benchmarkMethod", "backend", "readAheadChunks", "measurementInvocation")
        )
        if key in actual:
            raise ValueError(f"Duplicate read-ahead telemetry observation: {key}")
        actual.add(key)
        counters = item["counters"]
        if (
            item["backend"] != "postgresql"
            or item["enabled"] is not True
            or not item["serverVersion"]
            or any(type(counters.get(name)) is not int or counters[name] < 0 for name in READ_COUNTERS)
        ):
            raise ValueError(f"Missing PostgreSQL read evidence: {key}")
        if datetime.fromisoformat(item["completedAt"]) < datetime.fromisoformat(item["startedAt"]):
            raise ValueError(f"Negative read-ahead telemetry window: {key}")
    if actual != expected:
        raise ValueError(f"Unexpected read-ahead telemetry coordinates: {sorted(actual ^ expected)}")
    scan_privacy(root, *reports)
    return len(actual)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("smoke", "full"))
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("reports", type=Path, nargs="*")
    args = parser.parse_args()
    count = validate(args.artifacts, args.profile, *args.reports)
    print(f"Validated {count} exact PostgreSQL read-ahead telemetry observations")
