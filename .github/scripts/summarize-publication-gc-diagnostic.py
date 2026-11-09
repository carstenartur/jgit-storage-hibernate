#!/usr/bin/env python3
"""Validate and describe four fixed GC diagnostics, without acceptance statistics."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

SPEC = importlib.util.spec_from_file_location(
    "paired_publication", Path(__file__).with_name("summarize-publication-nonregression.py"))
PAIRED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAIRED)
require = PAIRED.require
POLICIES = ["size", "contention-aware", "contention-aware", "size"]
LOG_OPTIONS = (
    ("-Xlog:gc*=info,gc+heap=debug,gc+humongous=debug,gc+alloc=trace:file=", "-gc.log",
     ":uptimemillis,level,tags:filecount=2,filesize=16m"),
    ("-Xlog:gc+jni=debug:file=", "-jni.log",
     ":uptimemillis,level,tags:filecount=2,filesize=16m"),
    ("-Xlog:gc+init=info:file=", "-init.log", ":uptimemillis,level,tags"),
)


def validate_arguments(args, base):
    require(isinstance(args, list) and len(args) == 6
            and args[:2] == ["-Xms256m", "-Xmx1024m"]
            and all(isinstance(arg, str) and "\n" not in arg and "\r" not in arg for arg in args),
            "Diagnostic JVM arguments changed")
    require(args[2].startswith("-Djgit.storage.benchmark.connection-properties-file=/")
            and args[2].endswith(".properties") and "jdbc:" not in args[2],
            "Private connection-file JVM arguments differ")
    for arg, (prefix, ending, suffix) in zip(args[3:], LOG_OPTIONS):
        require(arg.startswith(prefix) and arg.endswith(suffix), "Diagnostic logging arguments changed")
        path = arg[len(prefix):-len(suffix)]
        require(path.startswith("/") and ":" not in path and "/../" not in path
                and path.endswith("/" + base + ending), "Diagnostic logging arguments name another fork")


def describe_logs(root, base, output, status):
    files = []
    missing = []
    groups = {}
    for _, suffix, _ in LOG_OPTIONS:
        candidates = sorted((root / "raw").glob(Path(base).name + suffix + "*"))
        allowed = {Path(base).name + suffix}
        if suffix != "-init.log":
            allowed |= {Path(base).name + suffix + ".0", Path(base).name + suffix + ".1"}
        for path in candidates:
            require(path.name in allowed and path.is_file() and not path.is_symlink(),
                    "Unexpected diagnostic log segment")
            maximum = 64 * 1024 if suffix == "-init.log" else 16 * 1024**2 + 64 * 1024
            require(path.stat().st_size <= maximum, "Diagnostic log exceeded its fixed size bound")
        files.extend(candidates)
        groups[suffix] = "\n".join(path.read_text(errors="strict") for path in candidates)
        # JNI logging can legitimately remain empty when no critical-region stalls occur.
        if not candidates or (suffix != "-jni.log" and not groups[suffix].strip()):
            missing.append(suffix)
    require(status != "completed" or not missing, "Completed fork lacks required diagnostic logs")
    gc = groups["-gc.log"]
    # Unified logs emit a start and a completed line for many pauses. Occupancy
    # appears on the completed line; count each GC ID once across rotation.
    collections = {}
    for line in gc.splitlines():
        match = re.search(r"GC\((\d+)\).* (\d+)M->(\d+)M\((\d+)M\)", line)
        if match:
            collections[int(match[1])] = (int(match[2]), int(match[3]), line)
    return {
        "logs": [str(path.relative_to(root)) for path in files],
        "missingLogKinds": missing,
        "gcCollections": len(collections),
        "gcLockerCollections": sum("GCLocker Initiated GC" in item[2] for item in collections.values()),
        "fullCollections": sum("Pause Full" in item[2] for item in collections.values()),
        "maximumPostGcMiB": max((item[1] for item in collections.values()), default=None),
        "minimumPostGcMiB": min((item[1] for item in collections.values()), default=None),
        "maximumReclaimedMiB": max((item[0] - item[1] for item in collections.values()), default=None),
        "outOfMemoryErrors": output.count("OutOfMemoryError"),
        "gcLockerRetryWarnings": (output + "\n" + gc).count("Retried waiting for GCLocker too often"),
        "jniAllocationStalls": groups["-jni.log"].count("Allocation failed. Thread stalled by JNI critical section"),
    }


def summarize(root):
    root = root.resolve()
    protocol = PAIRED.load_json(root / "protocol.json")
    variant = protocol.get("variant", "original")
    require(variant in {"original", "reftable"}, "Unknown diagnostic variant")
    require(protocol.get("schema") == "jgit-publication-gc-diagnostic/v1"
            and protocol.get("diagnosticOnly") is True
            and protocol.get("forkPolicies") == POLICIES
            and protocol.get("cell") == "independent-16w-0ms"
            and protocol.get("jdkRuntime") == "21.0.13+9"
            and protocol.get("originalStudyRun") == 42431132779,
            "Diagnostic protocol changed")
    require(not {"pairStages", "marginPercent", "order"} & protocol.keys(),
            "Diagnostic must not resume the paired stopping rule")
    baseline_protocol = dict(protocol, schema="jgit-publication-paired/v1", pairStages=[24, 96],
                             marginPercent=-5.0, order="odd-size-first-even-contention-first")
    PAIRED.validate_protocol(baseline_protocol)
    environment = PAIRED.load_json(root / "environment.json")
    require(environment.get("javaVersion") == "21.0.13"
            and re.fullmatch(r"21\.0\.13\+9(?:-LTS)?", environment.get("javaVmVersion", "")),
            "Exact diagnostic runtime differs")
    for key in ("sourceCommit", "sourceTree"):
        require(environment.get(key) == protocol[key], "Diagnostic source differs")
    trials = PAIRED.load_json(root / "trials.json")
    outcomes = PAIRED.load_json(root / "outcomes.json")
    require(isinstance(trials, list) and isinstance(outcomes, list)
            and 0 <= len(outcomes) <= len(trials) <= 4 and len(trials) - len(outcomes) <= 1,
            "Diagnostic attempted an unexpected number of forks")
    complete = len(outcomes) == 4 and all(o.get("status") != "interrupted" for o in outcomes)
    if complete:
        PAIRED.validate_environment(environment, baseline_protocol, 0)
    forks = []
    previous_end = None
    for index, trial in enumerate(trials):
        ordinal, policy = index + 1, POLICIES[index]
        require(trial.get("ordinal") == ordinal and trial.get("policy") == policy,
                "Diagnostic trial order changed")
        require(trial.get("sourceCommit") == protocol["sourceCommit"], "Diagnostic trial source differs")
        base = f"raw/fork-{ordinal:02d}-{policy}"
        require(trial.get("result") == base + ".json" and trial.get("output") == base + ".log",
                "Diagnostic result path changed")
        validate_arguments(trial.get("jvmArgs"), base)
        start = PAIRED.timestamp(trial.get("startedAt"))
        require(previous_end is None or start >= previous_end, "Diagnostic fork windows overlap")
        outcome = outcomes[index] if index < len(outcomes) else {
            "ordinal": ordinal, "policy": policy, "status": "incomplete", "failureType": None}
        require(outcome.get("ordinal") == ordinal and outcome.get("policy") == policy,
                "Diagnostic outcome order changed")
        status = outcome.get("status")
        require(status in {"completed", "failed", "interrupted", "incomplete"}, "Unknown fork outcome")
        if status != "incomplete":
            previous_end = PAIRED.timestamp(trial.get("completedAt"))
            require(previous_end >= start, "Diagnostic fork window runs backwards")
        if status in {"failed", "interrupted"}:
            require(isinstance(outcome.get("failureType"), str) and outcome["failureType"],
                    "Failed fork lost its exception type")
        else:
            require(outcome.get("failureType") is None, "Successful fork carries a failure type")
        output_path = root / trial["output"]
        output = output_path.read_text() if output_path.is_file() else ""
        details = describe_logs(root, base, output, status)
        if details["missingLogKinds"]:
            complete = False
        if status == "completed":
            raw = PAIRED.load_json(root / trial["result"])
            require(isinstance(raw, list) and len(raw) == 1, "Successful diagnostic JMH result is incomplete")
            require(raw[0].get("jvmArgs") == trial["jvmArgs"], "JMH arguments differ from the attempted fork")
            PAIRED.validate_fork(raw[0], baseline_protocol, policy, environment)
            require("# Run complete." in output and details["outOfMemoryErrors"] == 0,
                    "Successful diagnostic has failed or missing JMH output")
        forks.append(dict(outcome, **details))
    declared_bases = {f"fork-{i + 1:02d}-{POLICIES[i]}" for i in range(len(trials))}
    for path in (root / "raw").iterdir():
        require(any(path.name.startswith(base + ".") or path.name.startswith(base + "-")
                    for base in declared_bases), "Unmanifested diagnostic fork output")
    return {
        "schema": "jgit-publication-gc-diagnostic-summary/v1",
        "diagnosticOnly": True,
        "variant": variant,
        "sourceCommit": protocol["sourceCommit"],
        "sourceTree": protocol["sourceTree"],
        "originalStudyRun": 42431132779,
        "status": "complete-diagnostic" if complete else "incomplete-diagnostic",
        "attemptedForks": len(trials),
        "failedForks": sum(fork["status"] == "failed" for fork in forks),
        "forks": forks,
        "files": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in sorted(root.rglob("*")) if path.is_file() and path.name != "summary.json"},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    result = summarize(args.root)
    (args.root / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "attemptedForks", "failedForks")}))
