#!/usr/bin/env python3
"""Record exact runtime provenance and validate an observational ranked-scroll run."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
from pathlib import Path

SOURCES = (
    "reproducers/hibernate-search-ranked-scroll/pom.xml",
    "reproducers/hibernate-search-ranked-scroll/src/main/java/io/github/carstenartur/reproducer/RankedScrollMemoryProbe.java",
    "reproducers/hibernate-search-ranked-scroll/logging.properties",
    ".github/workflows/search-ranked-scroll-memory.yml",
    ".github/scripts/validate_ranked_scroll_evidence.py",
    ".github/scripts/validate_search_database_telemetry.py",
)
COLUMNS = (
    "phase", "consumed_rows", "live_field_docs", "field_doc_shallow_bytes",
    "score_doc_array_shallow_bytes",
)
FIELD_DOC = "org.apache.lucene.search.FieldDoc"
SCORE_ARRAY = "[Lorg.apache.lucene.search.ScoreDoc;"


def provenance(args):
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit) or args.documents < 1:
        raise ValueError("Require an exact source commit and positive document count")
    jars = {}
    classpath = (args.artifacts / "runtime-classpath.txt").read_text().strip()
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if not path.is_file() or path.suffix != ".jar" or path.name in jars:
            raise ValueError("Invalid or ambiguous resolved runtime classpath")
        jars[path.name] = {
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size,
        }
    return {
        "schemaVersion": 1, "sourceCommit": args.commit,
        "documents": args.documents, "chunkSize": 128,
        "searchVersion": args.search_version, "jdkBuild": args.java_version,
        "sourceFiles": {
            name: hashlib.sha256((args.source_root / name).read_bytes()).hexdigest()
            for name in SOURCES
        },
        "runtimeJars": jars,
    }


def histogram(path):
    lines = path.read_text().splitlines()
    if not lines or "#instances" not in lines[0] or not lines[-1].startswith("Total"):
        raise ValueError(f"Incomplete native histogram: {path.name}")
    tracked = {}
    for line in lines:
        fields = line.split()
        if len(fields) >= 4 and fields[3] in (FIELD_DOC, SCORE_ARRAY):
            if fields[3] in tracked:
                raise ValueError(f"Ambiguous class loaders in histogram: {path.name}")
            tracked[fields[3]] = (int(fields[1]), int(fields[2]))
    count, hit_bytes = tracked.get(FIELD_DOC, (0, 0))
    return count, hit_bytes, tracked.get(SCORE_ARRAY, (0, 0))[1]


def validate(args):
    root = args.artifacts
    recorded = json.loads((root / "provenance.json").read_text())
    if recorded != provenance(args):
        raise ValueError("Recorded source/runtime provenance differs from the files used")
    for name in ("dependency-tree.txt", "console.txt", "maven-build.log",
                 "maven-version.txt", "java-version.txt"):
        if not (root / name).is_file() or not (root / name).stat().st_size:
            raise ValueError(f"Missing retained native output: {name}")
    environment = dict(
        line.split("=", 1) for line in (root / "environment.txt").read_text().splitlines()
        if line.strip()
    )
    java = environment["java"]
    if java != args.java_version and not java.startswith(args.java_version + "-"):
        raise ValueError("Native JDK build differs from the pinned build")
    if environment["hibernate-search"] != args.search_version:
        raise ValueError("Native Search version differs from the matrix coordinate")
    jars = recorded["runtimeJars"]
    required = {
        f"hibernate-search-{name}-{args.search_version}.jar"
        for name in ("mapper-orm", "mapper-pojo-base", "engine", "backend-lucene", "util-common")
    }
    required.update({f"hibernate-core-{environment['hibernate']}.jar",
                     f"lucene-core-{environment['lucene']}.jar"})
    if not required.issubset(jars) or any(
        name.startswith("hibernate-search-") and not name.endswith(f"-{args.search_version}.jar")
        for name in jars
    ):
        raise ValueError("Runtime JAR versions disagree with the native version metadata")
    if (int(environment["documents"]) != args.documents
            or int(environment["chunk-size"]) != 128
            or int(environment["maximum-heap-bytes"]) != 512 * 1024 * 1024
            or environment["retained-hit-budget"] != "unset"):
        raise ValueError("Unexpected document/chunk/heap/budget coordinate")
    if int(environment["entity-loads"]) != 0 or int(environment["prepared-statements"]) != 0:
        raise ValueError("Unexpected entity loads or prepared SQL statements")
    if (int(environment["sessions-opened"]) < 1
            or environment["sessions-opened"] != environment["sessions-closed"]):
        raise ValueError("Unbalanced session lifecycle")

    with (root / "retained-hits.csv").open(newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != list(COLUMNS):
            raise ValueError("Unexpected retained-hit CSV columns")
        rows = list(reader)
    last_chunk = (args.documents + 127) // 128
    checkpoints = [("before_open", 0)]
    chunk = 1
    while chunk <= last_chunk:
        checkpoints.append((f"chunk_{chunk}", min(chunk * 128, args.documents)))
        chunk *= 2
    if checkpoints[-1][1] != args.documents:
        checkpoints.append((f"chunk_{last_chunk}", args.documents))
    checkpoints.append(("after_close", args.documents))
    if [(row["phase"], int(row["consumed_rows"])) for row in rows] != checkpoints:
        raise ValueError("Missing or incorrect traversal checkpoint")
    for row in rows:
        values = tuple(int(row[column]) for column in COLUMNS[2:])
        if min(values) < 0 or values != histogram(root / f"{row['phase']}.histogram.txt"):
            raise ValueError(f"CSV differs from native histogram at {row['phase']}")
    baseline = int(rows[0]["live_field_docs"])
    if int(rows[1]["live_field_docs"]) <= baseline:
        raise ValueError("No active ranked-hit sample; unsupported classification")
    if int(rows[-1]["live_field_docs"]) > baseline:
        raise ValueError("Ranked hits remain reachable after close")
    maximum = max(int(row["live_field_docs"]) for row in rows) - baseline
    print(f"Validated {args.documents} rows; sampled retained-hit maximum={maximum}; no memory bound asserted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "validate"))
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path.cwd())
    parser.add_argument("--commit", required=True)
    parser.add_argument("--documents", type=int, required=True)
    parser.add_argument("--search-version", required=True)
    parser.add_argument("--java-version", default="21.0.13+9")
    args = parser.parse_args()
    if args.command == "record":
        (args.artifacts / "provenance.json").write_text(
            json.dumps(provenance(args), indent=2, sort_keys=True) + "\n"
        )
    else:
        validate(args)


if __name__ == "__main__":
    main()
