#!/usr/bin/env python3
"""Validate exactly four controlled implementation pairs without policy acceptance."""

import argparse
import csv
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import statistics
from urllib.parse import unquote, urlparse


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


GC = module("controlled_gc", "summarize-publication-gc-diagnostic.py")
PREP = module("controlled_inputs", "prepare-publication-reftable-comparison.py")
PAIRED = GC.PAIRED
require = PAIRED.require
ORDER = ["baseline", "candidate", "candidate", "baseline", "baseline", "candidate", "candidate", "baseline"]
CRITICAL = 3.182446305284263
JMH_COMPILER_BLACKHOLE_ARGUMENTS = ["-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
                                   "-DcompilerBlackholesEnabled=true"]


def validate_variants(root, protocol):
    inputs = PAIRED.load_json(root / "variants.json")
    require(inputs.get("schema") == "jgit-reftable-variants/v1"
            and inputs.get("coreClass") == PREP.CORE_CLASS
            and inputs.get("harnessSourceCommit") == protocol["sourceCommit"]
            and inputs.get("harnessSourceTree") == protocol["sourceTree"]
            and inputs.get("sourceDifferences") == [PREP.SOURCE_PATH], "Prepared source identities differ")
    variants = inputs.get("variants", {})
    require(set(variants) == {"baseline", "candidate"}, "Unexpected implementation variants")
    for name, expected_source in (("baseline", PREP.BASELINE), ("candidate", PREP.CANDIDATE)):
        item = variants[name]
        require(item.get("sourceCommit") == expected_source
                and item.get("jar") == f"inputs/{name}-core.jar", "Library reference or JAR changed")
        for key in ("sourceTree", "buildCheckoutCommit", "buildCheckoutTree", "coreSourceTree"):
            require(re.fullmatch("[0-9a-f]{40}", str(item.get(key, ""))) is not None, f"Invalid library {key}")
        require(item["buildCheckoutCommit"] == expected_source,
                "Core build checkout differs")
        require(item["buildCheckoutTree"] == item["sourceTree"], "Core build tree differs")
        jar = PAIRED.evidence_path(root, item["jar"])
        require(PREP.sha(jar.read_bytes()) == item.get("jarSha256"), "Retained core JAR digest differs")
        entries = PREP.jar_entries(jar)
        require(entries == item.get("entries") and entries[PREP.CORE_CLASS] == item.get("classSha256"),
                "Retained core entry/class digest differs")
    old, new = variants["baseline"].get("guardedBlobs", {}), variants["candidate"].get("guardedBlobs", {})
    require(set(old) == set(new) and [name for name in old if old[name] != new[name]] == [PREP.SOURCE_PATH]
            and old.get(PREP.SOURCE_PATH) == PREP.BASELINE_BLOB
            and new.get(PREP.SOURCE_PATH) == PREP.CANDIDATE_BLOB, "Guarded production source differs")
    differences = PREP.validate_jar_difference(variants["baseline"]["entries"], variants["candidate"]["entries"])
    require(differences == inputs.get("compiledDifferences"), "Compiled difference inventory changed")
    return variants


def validate_identity(identity, trial, variant, protocol, environment):
    expected = {"schema": "jgit-publication-core-identity/v1", "ordinal": trial["ordinal"],
                "variant": trial["variant"], "harnessSourceCommit": protocol["sourceCommit"],
                "librarySourceCommit": variant["sourceCommit"], "librarySourceTree": variant["sourceTree"],
                "jarSha256": variant["jarSha256"], "classSha256": variant["classSha256"],
                "classResource": PREP.CORE_CLASS, "classpath": trial["classpath"],
                "javaVersion": environment["javaVersion"], "javaVmVersion": environment["javaVmVersion"],
                "iterationType": "WARMUP"}
    for key, value in expected.items():
        require(identity.get(key) == value, f"Actual child identity differs: {key}")
    jarpath = identity.get("jarPath", "")
    require(isinstance(jarpath, str) and jarpath.startswith("/")
            and jarpath.endswith("/" + variant["jar"]) and identity.get("codeSource") == jarpath,
            "Actual child CodeSource differs")
    classpath = trial.get("classpath")
    require(isinstance(classpath, str) and classpath.split(":").count(jarpath) == 1
            and all(path.startswith("/") and "*" not in path for path in classpath.split(":")),
            "Child classpath is not explicit with one selected core entry")
    resources = identity.get("resourceUrls", [])
    require(isinstance(resources, list) and len(resources) == 1 and isinstance(resources[0], str)
            and resources[0].startswith("jar:") and resources[0].endswith("!/" + PREP.CORE_CLASS),
            "Actual child core resource is missing or shadowed")
    require(unquote(urlparse(resources[0][4:].split("!/")[0]).path) == jarpath, "Core resource location differs")
    require(re.fullmatch(r"org\.openjdk\.jmh\.runner\.ForkedMain \S+ \d+", identity.get("command", "")) is not None,
            "Identity was not recorded in the JMH child")
    require(type(identity.get("pid")) is int and identity["pid"] > 0, "Invalid child PID")
    actual_args = identity.get("actualJvmArgs")
    expected_prefix = trial["jvmArgs"] + JMH_COMPILER_BLACKHOLE_ARGUMENTS
    require(isinstance(actual_args, list) and all(isinstance(arg, str) for arg in actual_args)
            and len(actual_args) == len(expected_prefix) + 1 and actual_args[:-1] == expected_prefix
            and re.fullmatch(r"-XX:CompileCommandFile=/.+", actual_args[-1]) is not None
            and identity.get("maxHeapBytes") == 1024**3, "Actual child heap/JVM arguments differ")
    moment = PAIRED.timestamp(identity.get("recordedAt"))
    require(PAIRED.timestamp(trial["startedAt"]) <= moment <= PAIRED.timestamp(trial["completedAt"]),
            "Child identity falls outside its trial")
    return ["<selected-core>" if entry == jarpath else entry for entry in classpath.split(":")]


def interval(ratios):
    require(len(ratios) == 4, "Exactly four complete pairs are required")
    logs = [math.log(PAIRED.number(value, "ratio", positive=True)) for value in ratios]
    center = statistics.mean(logs)
    spread = statistics.stdev(logs)
    radius = CRITICAL * spread / 2
    return {"pairs": 4, "confidence": 0.95, "degreesOfFreedom": 3,
            "changePercent": 100 * math.expm1(center),
            "lowerPercent": 100 * math.expm1(center - radius),
            "upperPercent": 100 * math.expm1(center + radius), "logRatioStandardDeviation": spread}


def summarize(root):
    root = root.resolve()
    protocol = PAIRED.load_json(root / "protocol.json")
    require(protocol.get("schema") == "jgit-reftable-controlled/v1" and protocol.get("diagnosticOnly") is True
            and protocol.get("forkVariants") == ORDER and protocol.get("pairCount") == 4
            and protocol.get("policies") == ["size"] and protocol.get("cell") == "independent-16w-0ms"
            and protocol.get("jdkRuntime") == "21.0.13+9"
            and not {"pairStages", "marginPercent", "order"} & protocol.keys(), "Controlled protocol changed")
    checked_protocol = dict(protocol, schema="jgit-publication-paired/v1", pairStages=[24, 96],
                            marginPercent=-5.0, order="odd-size-first-even-contention-first",
                            policies=["size", "contention-aware"])
    PAIRED.validate_protocol(checked_protocol)
    variants = validate_variants(root, protocol)
    environment = PAIRED.load_json(root / "environment.json")
    require(environment.get("javaVersion") == "21.0.13" and environment.get("osName") == "Linux"
            and re.fullmatch(r"21\.0\.13\+9(?:-LTS)?", environment.get("javaVmVersion", "")) is not None,
            "Controlled runtime differs")
    for key in ("sourceCommit", "sourceTree"):
        require(environment.get(key) == protocol[key], "Controlled environment source differs")
    trials = PAIRED.load_json(root / "trials.json")
    outcomes = PAIRED.load_json(root / "outcomes.json")
    require(isinstance(trials, list) and isinstance(outcomes, list)
            and 0 <= len(outcomes) <= len(trials) <= 8 and len(trials) - len(outcomes) <= 1,
            "Unexpected number of fixed attempts")
    complete = len(outcomes) == 8 and all(item.get("status") != "interrupted" for item in outcomes)
    if complete: PAIRED.validate_environment(environment, checked_protocol, 0)
    forks, pids, common_classpath = [], set(), None
    previous_end = None
    for index, trial in enumerate(trials):
        ordinal, pair, position, name = index + 1, index // 2 + 1, index % 2, ORDER[index]
        variant = variants[name]
        base = f"raw/pair-{pair:02d}-{name}"
        for key, value in {"ordinal": ordinal, "pair": pair, "position": position, "variant": name,
                           "policy": "size", "sourceCommit": protocol["sourceCommit"],
                           "librarySourceCommit": variant["sourceCommit"], "jarSha256": variant["jarSha256"],
                           "classSha256": variant["classSha256"], "identity": base + "-identity.json",
                           "result": base + ".json", "output": base + ".log"}.items():
            require(trial.get(key) == value, f"Controlled trial changed: {key}")
        GC.validate_arguments(trial.get("jvmArgs"), base)
        start = PAIRED.timestamp(trial.get("startedAt"))
        require(previous_end is None or start >= previous_end, "Controlled forks overlap")
        outcome = outcomes[index] if index < len(outcomes) else dict(ordinal=ordinal, variant=name, status="incomplete", failureType=None)
        require(outcome.get("ordinal") == ordinal and outcome.get("variant") == name, "Outcome order differs")
        status = outcome.get("status")
        require(status in {"completed", "failed", "interrupted", "incomplete"}, "Invalid fork status")
        if trial.get("completedAt") is not None:
            previous_end = PAIRED.timestamp(trial["completedAt"])
            require(previous_end >= start, "Fork window runs backwards")
        elif status != "incomplete": raise ValueError("Attempted outcome lacks its completion time")
        if status in {"failed", "interrupted"}:
            require(isinstance(outcome.get("failureType"), str) and outcome["failureType"], "Missing child failure type")
        else: require(outcome.get("failureType") is None, "Unexpected child failure type")
        output_path = root / trial["output"]
        output = output_path.read_text() if output_path.is_file() else ""
        details = GC.describe_logs(root, base, output, status)
        identity_path = root / trial["identity"]
        identity = PAIRED.load_json(identity_path) if identity_path.is_file() else None
        if identity is not None:
            normalized = validate_identity(identity, trial, variant, protocol, environment)
            require(identity["pid"] not in pids, "A measured fork reused a child PID")
            pids.add(identity["pid"])
            require(common_classpath is None or common_classpath == normalized, "Non-core child classpath changed")
            common_classpath = normalized
        metrics = None
        if status == "completed":
            require(identity is not None and not (root / (base + "-identity-failure.json")).exists(),
                    "Completed fork lacks valid child identity")
            require("# Run complete." in output and details["outOfMemoryErrors"] == 0, "Completed fork has failed/missing output")
            raw = PAIRED.load_json(root / trial["result"])
            require(isinstance(raw, list) and len(raw) == 1 and raw[0].get("jvmArgs") == trial["jvmArgs"], "JMH fork result or arguments differ")
            throughput = PAIRED.validate_fork(raw[0], checked_protocol, "size", environment)
            PAIRED.validate_checkpoint(trial.get("checkpointBefore"), "before")
            PAIRED.validate_checkpoint(trial.get("checkpointAfter"), "after")
            metrics = {"throughput": throughput, **{key: raw[0]["secondaryMetrics"][key]["score"] for key in PAIRED.SECONDARY_UNITS}}
        if details["missingLogKinds"]: complete = False
        forks.append(dict(outcome, pair=pair, position=position, childIdentity="verified" if identity else "missing",
                          metrics=metrics, **details))
    allowed = {f"pair-{index // 2 + 1:02d}-{ORDER[index]}" for index in range(len(trials))}
    for path in (root / "raw").iterdir():
        require(any(path.name.startswith(base + ".") or path.name.startswith(base + "-") for base in allowed),
                "Unmanifested fork evidence")
    pairs = []
    for pair in range(1, 5):
        found = {fork["variant"]: fork for fork in forks if fork["pair"] == pair and fork["status"] == "completed"}
        row = {"pair": pair, "status": "complete" if set(found) == {"baseline", "candidate"} else "incomplete"}
        if row["status"] == "complete":
            old, new = found["baseline"]["metrics"], found["candidate"]["metrics"]
            row.update(baselineThroughput=old["throughput"], candidateThroughput=new["throughput"],
                       throughputRatio=new["throughput"] / old["throughput"],
                       baselineAllocation=old["gc.alloc.rate.norm"], candidateAllocation=new["gc.alloc.rate.norm"])
        pairs.append(row)
    all_pairs = complete and all(pair["status"] == "complete" for pair in pairs)
    return {"schema": "jgit-reftable-controlled-summary/v1", "diagnosticOnly": True, "policyQualification": False,
            "sourceCommit": protocol["sourceCommit"], "sourceTree": protocol["sourceTree"],
            "status": "complete-comparison" if all_pairs else "incomplete-comparison", "attemptedForks": len(trials),
            "failedForks": sum(fork["status"] == "failed" for fork in forks), "forks": forks, "pairs": pairs,
            "throughputInterval": interval([pair["throughputRatio"] for pair in pairs]) if all_pairs else None,
            "files": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in sorted(root.rglob("*")) if path.is_file() and path.name not in {"summary.json", "pairs.csv"}}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    result = summarize(args.root)
    (args.root / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    fields = ["pair", "status", "baselineThroughput", "candidateThroughput", "throughputRatio", "baselineAllocation", "candidateAllocation"]
    with (args.root / "pairs.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
        writer.writeheader(); writer.writerows(result["pairs"])
    print(json.dumps({key: result[key] for key in ("status", "attemptedForks", "failedForks", "throughputInterval")}))
