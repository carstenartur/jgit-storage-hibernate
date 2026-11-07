#!/usr/bin/env python3
"""Check original consumer JMH files, harness provenance, and normalized counters."""

import argparse
import csv
from decimal import Decimal
import gzip
import hashlib
import json
import math
from pathlib import Path


KEYS = (
    "suite", "librarySha", "shape", "operation", "coordinate", "writer", "pressure",
    "chunkBatch", "measurement",
)
COUNTERS = (
    "payloadBytes", "preparedStatements", "transactions", "databaseBytesRead",
    "databaseBytesWritten", "jdbcBatches", "retainedAnalysisBytes", "javaFilesAnalyzed",
    "resultCount",
)
GC_METRICS = ("gc.alloc.rate.norm", "gc.count")


def read_bytes(path):
    data = path.read_bytes()
    return gzip.decompress(data) if path.suffix == ".gz" else data


def verified_bytes(root, relative, expected_hash):
    data = read_bytes(root / relative)
    if hashlib.sha256(data).hexdigest() != expected_hash:
        raise ValueError(f"SHA-256 mismatch: {relative}")
    return data


def samples(metric, label):
    raw = metric.get("rawData")
    if not isinstance(raw, list) or not raw or any(not isinstance(fork, list) for fork in raw):
        raise ValueError(f"Missing per-iteration rawData: {label}")
    result = [value for fork in raw for value in fork]
    if not result or any(not isinstance(value, (int, float)) or not math.isfinite(value) for value in result):
        raise ValueError(f"Invalid per-iteration measurement: {label}")
    return result


def normalized_rows(series, raw_file, records):
    for record in records:
        operation = record["benchmark"].rsplit(".", 1)[-1]
        if not operation.startswith(raw_file["shape"]):
            raise ValueError(f"Wrong consumer shape for {operation}")
        primary = samples(record["primaryMetric"], operation)
        if any(value <= 0 for value in primary):
            raise ValueError(f"Nonpositive JMH measurement: {operation}")
        secondary = record["secondaryMetrics"]
        for name in GC_METRICS:
            if name not in secondary:
                raise ValueError(f"Missing {name}: {operation}")
        measured = {}
        for name in (*COUNTERS, *GC_METRICS):
            if name in secondary:
                measured[name] = samples(secondary[name], f"{operation}/{name}")
                if len(measured[name]) != len(primary):
                    raise ValueError(f"Mismatched iteration count: {operation}/{name}")
        params = record["params"]
        for index in range(len(primary)):
            row = {
                "suite": series["suite"],
                "librarySha": series["librarySha"],
                "shape": raw_file["shape"],
                "operation": operation,
                "coordinate": str(params["coordinate"]),
                "writer": str(params.get("writer", "")),
                "pressure": str(params.get("pressure", "")),
                "chunkBatch": str(params.get("chunkBatch", "")),
                "measurement": str(index + 1),
            }
            row.update({name: str(measured[name][index]) if name in measured else "" for name in COUNTERS})
            yield row


def verify(manifest_path):
    manifest_path = Path(manifest_path)
    root = manifest_path.parent
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported evidence manifest schema")
    for revision, harness in manifest["harnesses"].items():
        combined = b"".join(read_bytes(root / source) for source in harness["sources"])
        if hashlib.sha256(combined).hexdigest() != harness["sourcesSha256"]:
            raise ValueError(f"Harness SHA-256 mismatch: {revision}")

    expected = {}
    raw_file_count = 0
    record_count = 0
    for series in manifest["series"]:
        if series["harnessSha"] not in manifest["harnesses"]:
            raise ValueError(f"Missing retained harness: {series['suite']}")
        for raw_file in series["raw"]:
            data = verified_bytes(root, raw_file["json"], raw_file["jsonSha256"])
            verified_bytes(root, raw_file["log"], raw_file["logSha256"])
            records = json.loads(data)
            if not isinstance(records, list) or not records:
                raise ValueError(f"Empty JMH file: {raw_file['json']}")
            raw_file_count += 1
            record_count += len(records)
            for row in normalized_rows(series, raw_file, records):
                key = tuple(row[name] for name in KEYS)
                if key in expected:
                    raise ValueError(f"Duplicate raw coordinate: {key}")
                expected[key] = row

    with (root / manifest["normalizedCsv"]).open(newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != [*KEYS, *COUNTERS]:
            raise ValueError("Unexpected normalized CSV columns")
        actual = {}
        for row in reader:
            key = tuple(row[name] for name in KEYS)
            if key in actual:
                raise ValueError(f"Duplicate CSV coordinate: {key}")
            actual[key] = row
    if actual.keys() != expected.keys():
        raise ValueError(f"Missing or additional observations: CSV={len(actual)}, raw={len(expected)}")
    for key, raw_row in expected.items():
        for name in COUNTERS:
            left, right = actual[key][name], raw_row[name]
            equal = left == right if not left or not right else Decimal(left) == Decimal(right)
            if not equal:
                raise ValueError(f"Counter mismatch {name} at {key}: CSV={left!r}, raw={right!r}")
    return {"observations": len(expected), "rawFiles": raw_file_count, "jmhCoordinates": record_count}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.manifest), sort_keys=True))
