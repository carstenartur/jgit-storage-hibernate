#!/usr/bin/env python3
"""Verify retained native Taxonomy artifacts and reproduce their CSV summary."""

import argparse
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET
import zipfile


OPERATIONS = {
    "taxonomyExactPathSummary",
    "taxonomyProjectionRebuild",
    "taxonomySqlPathSummary",
    "taxonomyTextEntity",
    "taxonomyTextSummary",
}
FIELDS = (
    "requestedRttMillis", "calibratedMedianMicros", "operation", "historyCommits",
    "wallMillis", "resultCount", "preparedStatements", "transactions", "jdbcBatches",
    "wholeIterationAllocatedBytes", "wholeIterationGcCount", "indexBytes",
    "walBytes", "walInsertLsnBytes", "dbTuplesInserted", "dbTuplesDeleted",
)
ROOT = "target/taxonomy-postgres/"


def one_sample(metric):
    raw = metric["rawData"]
    if len(raw) != 1 or len(raw[0]) != 1:
        raise ValueError("Expected exactly one fork and one single-shot observation")
    value = raw[0][0]
    if not math.isfinite(value):
        raise ValueError("Nonfinite observation")
    return value


def verify(manifest_path):
    manifest_path = Path(manifest_path)
    root = manifest_path.parent
    manifest = json.loads(manifest_path.read_text())
    if manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported manifest schema")
    if manifest["localSourceTree"] != manifest["workflowSourceTree"]:
        raise ValueError("Reviewed and executed source trees differ")
    if (len(manifest["artifacts"]) != 2
            or {value["requestedRttMillis"] for value in manifest["artifacts"]} != {0, 20}):
        raise ValueError("Expected both native delay cells exactly once")
    rows = []
    checked = []
    for artifact in manifest["artifacts"]:
        data = (root / artifact["file"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != artifact["sha256"]:
            raise ValueError(f"Archive SHA-256 mismatch: {artifact['file']}")
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            def load(name):
                return json.loads(archive.read(ROOT + name))

            fixture = load("fixture.json")
            for name, digest in fixture["filesSha256"].items():
                if hashlib.sha256(archive.read(ROOT + name)).hexdigest() != digest:
                    raise ValueError(f"File SHA-256 mismatch: {artifact['file']}/{name}")
            if any(fixture[key] != manifest["workflowSha"]
                   for key in ("checkoutSha", "harnessSha", "librarySha")):
                raise ValueError("Unexpected executed source revision")
            if int(fixture["workflowRunId"]) != manifest["workflowRunId"]:
                raise ValueError("Unexpected workflow run")
            if fixture["samplesPerCoordinate"] != 1 or fixture["cacheState"] != "reopened":
                raise ValueError("Unexpected measurement or cache profile")

            rtt = artifact["requestedRttMillis"]
            calibration = load("taxonomy-rtt-calibration.json")
            probes = calibration["samplesMicros"]
            if calibration != fixture["network"]["calibration"]:
                raise ValueError("Calibration and fixture provenance differ")
            if (calibration["requestedRttMillis"] != rtt
                    or len(probes) != calibration["sampleCount"] or len(probes) != 20
                    or int(statistics.median(probes)) != calibration["medianMicros"]
                    or sorted(probes)[18] != calibration["p95Micros"]):
                raise ValueError("Invalid round-trip calibration summary")
            if rtt and calibration["medianMicros"] < rtt * 800:
                raise ValueError("Configured delay did not appear in the probe")

            recovery = load("taxonomy-recovery.json")
            expected_recovery = {
                "fixtureCommits": 1000, "affectedWorkspaceCommits": 100,
                "partialProjections": 50, "projectionsAfterProviderRestart": 50,
                "recoveredProjections": 100, "gitVerifiedHits": 58,
            }
            if recovery != expected_recovery:
                raise ValueError("Unexpected interrupted-rebuild recovery result")

            raw = load("taxonomy.json")
            observations = load("taxonomy-native.json")["observations"]
            ndjson = [json.loads(line) for line in archive.read(
                ROOT + "taxonomy-native.ndjson").splitlines() if line]
            if observations != ndjson:
                raise ValueError("Aggregate telemetry differs from original NDJSON")
            native = {value["coordinate"]["operation"]: value for value in observations}
            by_operation = {value["benchmark"].rsplit(".", 1)[-1]: value for value in raw}
            if (len(raw) != 5 or len(observations) != 5
                    or set(native) != OPERATIONS or set(by_operation) != OPERATIONS):
                raise ValueError("Incomplete or duplicate operation coverage")

            for operation in sorted(OPERATIONS):
                record, telemetry = by_operation[operation], native[operation]
                params, coordinate = record["params"], telemetry["coordinate"]
                expected_params = {
                    "coordinate": "1000", "cacheState": "reopened",
                    "deployment": f"postgresql-rtt-{rtt}ms",
                    "indexBatch": "50", "purgeBatch": "250",
                }
                if (params != expected_params
                        or any(coordinate[key] != value for key, value in params.items())
                        or coordinate["measurement"] != "1"):
                    raise ValueError("JMH and native coordinates differ")
                if record["mode"] != "ss" or record["primaryMetric"]["scoreUnit"] != "ms/op":
                    raise ValueError("Unexpected JMH measurement mode or unit")
                if not telemetry["enabled"] or telemetry["serverVersion"] != "17.10":
                    raise ValueError("Unexpected PostgreSQL telemetry source")
                secondary, counters = record["secondaryMetrics"], telemetry["counters"]
                index_bytes = telemetry["gauges"]["consumer.lucene.index_bytes"]
                if index_bytes <= 0 or one_sample(record["primaryMetric"]) <= 0:
                    raise ValueError("Empty index or invalid operation duration")
                rows.append({
                    "requestedRttMillis": rtt,
                    "calibratedMedianMicros": calibration["medianMicros"],
                    "operation": operation, "historyCommits": int(params["coordinate"]),
                    "wallMillis": one_sample(record["primaryMetric"]),
                    **{key: one_sample(secondary[key]) for key in
                       ("resultCount", "preparedStatements", "transactions", "jdbcBatches")},
                    "wholeIterationAllocatedBytes": one_sample(secondary["gc.alloc.rate.norm"]),
                    "wholeIterationGcCount": one_sample(secondary["gc.count"]),
                    "indexBytes": index_bytes,
                    "walBytes": counters["postgresql.wal.bytes"],
                    "walInsertLsnBytes": counters["postgresql.wal.insert_lsn_bytes"],
                    "dbTuplesInserted": counters["postgresql.database.tuples_inserted"],
                    "dbTuplesDeleted": counters["postgresql.database.tuples_deleted"],
                })

            def count(operation, key):
                return one_sample(by_operation[operation]["secondaryMetrics"][key])

            if (count("taxonomyExactPathSummary", "resultCount")
                    != count("taxonomySqlPathSummary", "resultCount")
                    or count("taxonomyTextEntity", "resultCount")
                    != count("taxonomyTextSummary", "resultCount")
                    or count("taxonomyProjectionRebuild", "resultCount") != 1000):
                raise ValueError("Unexpected workload membership counts")
            suites = []
            for name in archive.namelist():
                if "/surefire-reports/TEST-" in name and name.endswith(".xml"):
                    suite = ET.fromstring(archive.read(name))
                    suites.append({key: int(suite.attrib[key])
                                   for key in ("tests", "failures", "errors", "skipped")})
            total = {key: sum(suite[key] for suite in suites)
                     for key in ("tests", "failures", "errors", "skipped")}
            if len(suites) != 2 or total != {"tests": 6, "failures": 0, "errors": 0, "skipped": 0}:
                raise ValueError("Unexpected native lifecycle test results")
            checked.append({"rttMillis": rtt, "observations": 5, **total})

    with (root / manifest["summaryCsv"]).open(newline="") as stream:
        reader = csv.DictReader(stream)
        expected_rows = [{key: str(row[key]) for key in FIELDS} for row in rows]
        if reader.fieldnames != list(FIELDS) or list(reader) != expected_rows:
            raise ValueError("CSV does not reproduce the original artifact observations")
    return {"workflowRunId": manifest["workflowRunId"],
            "observations": len(rows), "artifacts": checked}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.manifest), sort_keys=True))
