#!/usr/bin/env python3
"""Validate the preregistered paired study and retain unfavorable findings."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

PAIR_STAGES = [24, 96]
POLICIES = ["size", "contention-aware"]
INDEPENDENT_CRITICAL = {24: 2.709720420119227, 96: 2.5461530351050503}
SHARED_CRITICAL = 2.0686576104190486  # t(23), two-sided 95%
JDK25_CELL = "independent-16w-0ms-jdk25"
JDK25_PAIRS = 16
JDK25_CRITICAL = 1.7530503556925547  # t(15), one-sided 95% lower bound
CELLS = {
    "independent-1w-0ms": ("independent", 1, 256, 0),
    "independent-1w-50ms": ("independent", 1, 256, 50),
    "independent-16w-0ms": ("independent", 16, 256, 0),
    JDK25_CELL: ("independent", 16, 256, 0),
    "independent-16w-50ms": ("independent", 16, 256, 50),
    "shared-8w-5ms": ("shared", 8, 768, 5),
}
SECONDARY_UNITS = {
    "publications": "#",
    "directPublicationSelections": "#",
    "prePersistedPublicationSelections": "#",
    "storageTransactions": "#",
    "gc.alloc.rate.norm": "B/op",
    "packAndRefPublicationDuration:p0.50": "us/op",
    "packAndRefPublicationDuration:p0.95": "us/op",
    "packAndRefPublicationDuration:p0.99": "us/op",
    "packPublicationLockAcquisition:p0.95": "us/op",
    "publicationObservedHeapPeak": "bytes",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def number(value, label: str, positive: bool = False) -> float:
    require(isinstance(value, (int, float)) and not isinstance(value, bool), f"{label}: not numeric")
    result = float(value)
    require(math.isfinite(result), f"{label}: not finite")
    require(not positive or result > 0, f"{label}: not positive")
    return result


def load_json(path: Path):
    def reject(value):
        raise ValueError(f"{path.name}: non-JSON constant {value}")

    try:
        return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject)
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read JSON evidence {path.name}: {exc}") from exc


def evidence_path(root: Path, relative: str) -> Path:
    require(isinstance(relative, str), "Evidence path is not text")
    path = (root / relative).resolve()
    require(path.is_relative_to(root) and path.is_file(), f"Missing/unsafe evidence path: {relative}")
    return path


def timestamp(value: str) -> datetime:
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as exc:
        raise ValueError(f"Invalid trial timestamp: {value}") from exc
    require(result.tzinfo is not None, "Trial timestamps must identify their timezone")
    return result


def paired_interval(ratios: list[float], critical: float) -> dict:
    count = len(ratios)
    require(count in (JDK25_PAIRS, *PAIR_STAGES), "Expected a preregistered pair count")
    logs = [math.log(number(value, "paired ratio", positive=True)) for value in ratios]
    mean = statistics.mean(logs)
    half_width = critical * statistics.stdev(logs) / math.sqrt(count)
    return {
        "changePercent": 100 * math.expm1(mean),
        "lowerPercent": 100 * math.expm1(mean - half_width),
        "upperPercent": 100 * math.expm1(mean + half_width),
        "logRatioStandardDeviation": statistics.stdev(logs),
    }


def validate_protocol(protocol: dict) -> tuple:
    require(isinstance(protocol, dict), "Protocol must be an object")
    cell = protocol.get("cell")
    require(cell in CELLS, f"Unknown preregistered cell: {cell}")
    layout, writers, payload, rtt = CELLS[cell]
    jdk25_replication = cell == JDK25_CELL
    pair_stages = PAIR_STAGES if layout == "independent" else [24]
    if jdk25_replication:
        pair_stages = [JDK25_PAIRS]
    expected = {
        "schema": "jgit-publication-paired-jdk25/v1" if jdk25_replication else "jgit-publication-paired/v1",
        "pairStages": pair_stages,
        "warmupIterations": 2,
        "warmupSeconds": 3,
        "measurementIterations": 3,
        "measurementSeconds": 5,
        "policies": POLICIES,
        "order": "odd-size-first-even-contention-first",
        "marginPercent": -5.0,
        "heapMiB": 1024,
        "initialHeapMiB": 256,
        "jdkMajor": 25 if jdk25_replication else 21,
        "jmhVersion": "1.37",
        "layout": layout,
        "writers": writers,
        "payloadKiB": payload,
        "requestedRttMillis": rtt,
        "backend": "postgresql-hikari",
        "writeMode": "stateful",
    }
    for key, value in expected.items():
        require(protocol.get(key) == value, f"Protocol changed: {key}")
    for key in ("sourceCommit", "sourceTree"):
        require(re.fullmatch(r"[0-9a-f]{40}", str(protocol.get(key, ""))) is not None, f"Invalid {key}")
    return layout, writers, payload, rtt


def validate_environment(environment: dict, protocol: dict, rtt: int) -> None:
    require(isinstance(environment, dict), "Environment must be an object")
    for key in ("sourceCommit", "sourceTree"):
        require(environment.get(key) == protocol[key], f"Environment {key} differs")
    if protocol["cell"] == JDK25_CELL:
        require(environment.get("javaVersion") == "25.0.5"
                and environment.get("javaVmVersion") == "25.0.5+8-LTS",
                "The separate replication requires Temurin 25.0.5+8")
    else:
        require(str(environment.get("javaVersion", "")).startswith("21."), "JDK 21 is required")
    for key in ("javaVm", "javaVmVersion", "osName", "osArch", "osVersion", "cpuMax",
                "memoryMax", "cpuModel", "cpuAffinity"):
        value = environment.get(key)
        require(isinstance(value, str) and bool(value.strip()), f"Missing or invalid environment {key}")
    processors = environment.get("availableProcessors")
    require(type(processors) is int and processors > 0, "Missing or invalid processor count")
    require(environment.get("databaseMajorVersion") == 17, "PostgreSQL 17 is required")
    require("PostgreSQL 17.10" in str(environment.get("databaseVersion", "")), "PostgreSQL version differs")
    for key in ("fsync", "synchronousCommit", "fullPageWrites"):
        require(environment.get(key) == "on", f"Non-durable database setting: {key}")
    require(environment.get("storage") == "host-bind", "Database data must use an explicit host bind")
    fs = environment.get("fileStoreType")
    require(isinstance(fs, str) and fs and fs.lower() not in {"tmpfs", "ramfs"}, "Database filesystem is memory backed or missing")
    for key in ("rttSamplesMillis", "finalRttSamplesMillis"):
        samples = environment.get(key)
        require(isinstance(samples, list) and len(samples) == 20, f"Twenty recorded {key} are required")
        values = [number(value, "RTT sample", positive=True) for value in samples]
        median = statistics.median(values)
        require(rtt <= median <= rtt + 25, f"Measured RTT does not match requested delay: {median}")
    require(timestamp(environment.get("completedAt")) >= timestamp(environment.get("startedAt")), "Environment window runs backwards")


def validate_checkpoint(value, label: str) -> None:
    require(isinstance(value, dict), f"Missing {label}")
    # PostgreSQL 17 pg_stat_checkpointer: retain activity/reset observations, never
    # exclude a fork because checkpoints happened. stats_reset may be SQL NULL.
    for key in ("num_timed", "num_requested", "restartpoints_timed", "restartpoints_req",
                "restartpoints_done", "buffers_written"):
        count = value.get(key)
        require(type(count) is int and count >= 0, f"Invalid {label}.{key}")
    for key in ("write_time", "sync_time"):
        require(number(value.get(key), label + "." + key) >= 0, f"Negative {label}.{key}")
    require("stats_reset" in value, f"Missing {label}.stats_reset")
    if value["stats_reset"] is not None:
        timestamp(value["stats_reset"])


def validate_fork(item: dict, protocol: dict, policy: str, environment: dict) -> float:
    method = "publishToSameRepository" if protocol["layout"] == "shared" else "publishToDifferentRepositories"
    expected = {
        "benchmark": "io.github.carstenartur.jgit.storage.hibernate.benchmark.ConcurrentPublicationBenchmark." + method,
        "mode": "thrpt",
        "threads": protocol["writers"],
        "forks": 1,
        "warmupIterations": 2,
        "warmupTime": "3 s",
        "measurementIterations": 3,
        "measurementTime": "5 s",
        "jmhVersion": protocol["jmhVersion"],
        "jdkVersion": environment["javaVersion"],
        "vmName": environment["javaVm"],
        "vmVersion": environment["javaVmVersion"],
    }
    for key, value in expected.items():
        require(item.get(key) == value, f"Unexpected JMH {key}: {item.get(key)}")
    expected_params = {
        "backend": "postgresql-hikari",
        "writeMode": "stateful",
        "payloadKiB": str(protocol["payloadKiB"]),
        "payloadMiB": "0",
        "publicationPolicy": policy,
        "deployment": "paired-" + protocol["cell"],
    }
    require(item.get("params") == expected_params, "Fork parameter coordinate differs from protocol/trial")
    args = item.get("jvmArgs", [])
    require(isinstance(args, list) and all(isinstance(arg, str) for arg in args), "Invalid JVM arguments")
    require([arg for arg in args if arg.startswith("-Xms")] == ["-Xms256m"]
            and [arg for arg in args if arg.startswith("-Xmx")] == ["-Xmx1024m"],
            "Fixed fork heap must have exactly one initial and maximum setting")
    require(not any(arg.startswith(("-XX:InitialHeapSize=", "-XX:MaxHeapSize=", "-XX:SoftMaxHeapSize=",
                                    "-XX:MaxRAM=", "-XX:InitialRAMPercentage=", "-XX:MaxRAMPercentage=",
                                    "-XX:MinRAMPercentage=")) for arg in args), "Conflicting heap option")
    require(not any("jdbc:" in arg or ".password=" in arg for arg in args), "JMH arguments contain connection details")
    if protocol["cell"] == JDK25_CELL:
        collectors = [arg for arg in args if re.fullmatch(r"-XX:[+-]Use\w+GC", arg)]
        require(collectors == ["-XX:+UseG1GC"], "The replication requires explicit G1")
    metric = item.get("primaryMetric", {})
    require(metric.get("scoreUnit") == "ops/s", "Unexpected throughput unit")
    score = number(metric.get("score"), "throughput", positive=True)
    raw = metric.get("rawData")
    require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], list) and len(raw[0]) == 3, "Fork does not contain three measured iterations")
    iteration_values = [number(value, "iteration throughput", positive=True) for value in raw[0]]
    require(math.isclose(score, statistics.mean(iteration_values), rel_tol=1e-8, abs_tol=1e-8), "Aggregate score differs from retained iterations")
    secondary = item.get("secondaryMetrics", {})
    for key, unit in SECONDARY_UNITS.items():
        require(key in secondary, f"Required secondary metric missing: {key}")
        require(secondary[key].get("scoreUnit") == unit, f"Unexpected secondary unit: {key}")
        value = number(secondary[key].get("score"), key)
        require(value >= 0, f"Negative counter: {key}")
    require(secondary["publications"]["score"] > 0, "Fork published no operations")
    return score


def summarize(root: Path) -> dict:
    root = root.resolve()
    protocol = load_json(root / "protocol.json")
    layout, _writers, _payload, rtt = validate_protocol(protocol)
    environment = load_json(root / "environment.json")
    validate_environment(environment, protocol, rtt)
    trials = load_json(root / "trials.json")
    independent = layout == "independent"
    jdk25_replication = protocol["cell"] == JDK25_CELL
    stages = protocol["pairStages"]
    expected_lengths = [2 * count for count in stages]
    require(isinstance(trials, list) and len(trials) in expected_lengths, "Incomplete preregistered fork stage")
    by_pair: dict[int, dict] = {}
    secondary_rows = []
    used_paths = set()
    source_files = {root / name for name in ("protocol.json", "environment.json", "trials.json")}
    previous_end = None
    for sequence, trial in enumerate(trials):
        require(isinstance(trial, dict), "Trial must be an object")
        pair = sequence // 2 + 1
        position = sequence % 2
        order = POLICIES if pair % 2 else list(reversed(POLICIES))
        policy = order[position]
        require(trial.get("pair") == pair and trial.get("position") == position and trial.get("policy") == policy, "Pair numbering/order is incomplete or relabelled")
        require(trial.get("sourceCommit") == protocol["sourceCommit"], "Trial source differs")
        for key in ("checkpointBefore", "checkpointAfter"):
            validate_checkpoint(trial.get(key), key)
        start, end = timestamp(trial.get("startedAt")), timestamp(trial.get("completedAt"))
        require(end > start and (previous_end is None or start >= previous_end), "Fork windows overlap or run backwards")
        previous_end = end
        raw_path = evidence_path(root, trial.get("result"))
        output_path = evidence_path(root, trial.get("output"))
        require(raw_path not in used_paths and output_path not in used_paths, "Duplicate evidence path")
        require(raw_path.suffix == ".json" and raw_path.parent == root / "raw", "Unexpected raw result location")
        require(output_path.suffix == ".log" and output_path.parent == root / "raw"
                and output_path.stem == raw_path.stem, "Original output must be the matching raw/*.log")
        used_paths.update((raw_path, output_path))
        source_files.update((raw_path, output_path))
        require(output_path.stat().st_size > 0, "Missing original JMH output")
        raw = load_json(raw_path)
        require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], dict), "Expected exactly one JMH result per fresh fork")
        score = validate_fork(raw[0], protocol, policy, environment)
        by_pair.setdefault(pair, {})[policy] = score
        secondary_rows.append({
            "pair": pair,
            "position": position,
            "policy": policy,
            "throughputOpsPerSecond": score,
            **{key: raw[0]["secondaryMetrics"][key]["score"] for key in SECONDARY_UNITS},
        })
    require(set((root / "raw").iterdir()) == used_paths,
            "Unlisted raw results or original logs would be silently excluded")
    require(timestamp(environment["startedAt"]) <= timestamp(trials[0]["startedAt"]), "Environment starts after the first fork")
    require(timestamp(environment["completedAt"]) >= previous_end, "Environment ended before the final fork")
    rows = []
    for pair, values in by_pair.items():
        require(set(values) == set(POLICIES), "Incomplete policy pair")
        rows.append({"pair": pair, "sizeOpsPerSecond": values["size"], "contentionOpsPerSecond": values["contention-aware"], "ratio": values["contention-aware"] / values["size"]})
    looks = []
    for count in stages:
        if count > len(rows):
            break
        if jdk25_replication:
            critical = JDK25_CRITICAL
        else:
            critical = INDEPENDENT_CRITICAL[count] if independent else SHARED_CRITICAL
        interval = paired_interval([row["ratio"] for row in rows[:count]], critical)
        looks.append({
            "pairCount": count,
            "criticalValue": critical,
            **interval,
            "nonRegressionEstablished": independent and interval["lowerPercent"] > -5.0,
        })
    if independent and not jdk25_replication:
        if len(rows) == 24:
            require(looks[0]["nonRegressionEstablished"], "First look did not pass; the preregistered 96-pair stage is required")
        else:
            require(not looks[0]["nonRegressionEstablished"], "Unplanned extension after a first-look pass")
    final = looks[-1]
    if jdk25_replication:
        interval_method = "fixed 16 pairs, one-sided 95% lower bound"
    elif independent:
        interval_method = "preregistered sequential lower bound, Bonferroni alpha=.05/(4 cells * 2 looks)"
    else:
        interval_method = "fixed 24 pairs, two-sided 95%"
    return {
        "schema": protocol["schema"],
        "cell": protocol["cell"],
        "sourceCommit": protocol["sourceCommit"],
        "sourceTree": protocol["sourceTree"],
        "pairCount": len(rows),
        "forkCount": len(trials),
        "throughputChangePercent": final["changePercent"],
        "lowerBoundPercent": final["lowerPercent"],
        "upperBoundPercent": final["upperPercent"],
        "logRatioStandardDeviation": final["logRatioStandardDeviation"],
        "criticalValue": final["criticalValue"],
        "intervalMethod": interval_method,
        "marginPercent": -5.0,
        "nonRegressionEstablished": final["nonRegressionEstablished"],
        "sharedImprovementEstablished": not independent and final["lowerPercent"] > 0.0,
        "stoppedEarly": independent and len(rows) == 24,
        "looks": looks,
        "requestedRttMillis": rtt,
        "medianRttMillis": statistics.median(environment["rttSamplesMillis"]),
        "pairs": rows,
        "secondaryObservations": secondary_rows,
        "secondaryUnits": SECONDARY_UNITS,
        "sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(source_files)},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    result = summarize(args.root)
    (args.root / "summary.json").write_text(json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    with (args.root / "pairs.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(result["pairs"][0]))
        writer.writeheader()
        writer.writerows(result["pairs"])
    with (args.root / "secondary.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(result["secondaryObservations"][0]))
        writer.writeheader()
        writer.writerows(result["secondaryObservations"])
    print(json.dumps({key: value for key, value in result.items() if key not in {"pairs", "sha256", "secondaryObservations"}}, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
