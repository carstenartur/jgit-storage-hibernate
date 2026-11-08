#!/usr/bin/env python3
"""Native publication artifact validation and exact harness/library provenance."""
import argparse
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess

RELEASE = "ba6aeecc338968df4279fb9c7c72b15bbb3577fb"
MAIN = "1c33640bc75206e81a9bc5722768860a2a2321f5"
MODULES = ("jgit-storage-hibernate-core", "jgit-storage-hibernate-search", "jgit-storage-hibernate-java-analysis")
DURABILITY_SETTINGS = ("fsync", "synchronous_commit", "full_page_writes")
PACKAGE = Path("java/io/github/carstenartur/jgit/storage/hibernate/benchmark")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def privacy(paths):
    scanned = 0
    for root in paths:
        require(not root.is_symlink(), "Artifact root is a symbolic link: " + str(root))
        if not root.exists():
            continue # Only one library checkout has reports in each matrix job.
        for path in itertools.chain((root,), root.rglob("*")):
            require(not path.is_symlink(), "Unscannable symbolic link in artifact: " + str(path))
            if path.is_file():
                data = path.read_bytes().lower()
                require(b"jdbc:postgresql" not in data and b"fixture-only" not in data,
                        "Connection metadata found in artifact: " + str(path))
                scanned += 1
            else:
                require(path.is_dir(), "Unscannable artifact entry: " + str(path))
    require(scanned > 0, "No artifact files were scanned")
    return scanned


def samples(metric):
    values = [value for fork in metric["rawData"] for value in fork]
    require(len(values) == 3 and all(math.isfinite(value) for value in values), "Need three finite per-iteration values")
    return values


def verify_shape(shape, raw, native):
    taxonomy = shape == "taxonomy"
    require(shape in ("taxonomy", "audio"), "Unknown publication shape")
    operation = "taxonomyPublishDelta" if taxonomy else "audioPublishWithWal"
    expected = {(1000, 1), (1000, 4)} if taxonomy else {(2097152, 1), (16777216, 1)}
    seen = set()
    for row in raw:
        params = row["params"]
        coordinate = int(params["coordinate"])
        workers = int(params["parallelism"]) if taxonomy else 1
        require(row["benchmark"].endswith("." + operation), "Unexpected benchmark operation")
        require((coordinate, workers) not in seen, "Duplicate JMH coordinate")
        seen.add((coordinate, workers))
        require(row["forks"] == 1 and row["warmupIterations"] == 1 and row["measurementIterations"] == 3,
                "Unexpected fork or iteration count")
        require(all(value > 0 for value in samples(row["primaryMetric"])), "Non-positive publication duration")
        metrics = row["secondaryMetrics"]
        exact = {"workspacesPublished": 4 if taxonomy else 1, "publicationWorkers": workers}
        if taxonomy:
            exact["commitsIndexed"] = 4
        else:
            require(params["analysisMiB"] == "32", "Audio pressure differs from fixed native pair")
            exact.update(payloadBytes=coordinate, retainedAnalysisBytes=32 * 1024 * 1024)
            require(all(value >= coordinate for value in samples(metrics["databaseBytesWritten"])),
                    "Audio storage bytes do not cover logical payload")
        for key, value in exact.items():
            require(samples(metrics[key]) == [value] * 3, "Wrong per-iteration " + key)
        require(all(value > 0 for value in samples(metrics["preparedStatements"])), "Missing JDBC work")
        for key in ("transactions", "jdbcBatches", "gc.alloc.rate.norm", "gc.count"):
            require(all(value >= 0 for value in samples(metrics[key])), "Negative " + key)
    require(seen == expected, "Incomplete JMH coordinate matrix")
    expected_native = {(coordinate, workers, measurement) for coordinate, workers in expected for measurement in (1, 2, 3)}
    observations = {}
    membership = {}
    for row in native:
        params = row["coordinate"]
        coordinate = int(params["coordinate"])
        workers = int(params["parallelism"]) if taxonomy else 1
        measurement = int(params["measurement"])
        publication = int(params["publication"])
        key = (coordinate, workers, measurement)
        require(key not in observations, "Duplicate native observation")
        require(publication == measurement + 1, "Warmup/publication sequence differs")
        require(params["operation"] == operation and row["enabled"] and row["backend"] == "postgresql",
                "Missing native PostgreSQL publication evidence")
        require(row["counters"].get("postgresql.wal.insert_lsn_bytes", 0) > 0, "Missing positive WAL insert-LSN delta")
        metadata = row["metadata"]
        require(all(metadata.get("consumer.postgresql." + setting) == "on" for setting in DURABILITY_SETTINGS),
                "Missing observed durable PostgreSQL settings on measured provider")
        require(metadata.get("consumer.reopened_verified") == "true", "Missing verified provider restart")
        digest = metadata.get("consumer.membership_sha256", "")
        require(re.fullmatch("[0-9a-f]{64}", digest), "Invalid membership digest")
        heads = metadata.get("consumer.heads", "").split(",")
        require(len(heads) == (4 if taxonomy else 2) and all(re.fullmatch("[0-9a-f]{40}", head) for head in heads),
                "Incomplete verified refs")
        expected_commits = coordinate + 4 * publication if taxonomy else 2 * (publication + 1)
        gauges = row["gauges"]
        require(gauges["consumer.git_commits"] == expected_commits, "Wrong complete Git membership size")
        require(gauges["consumer.sql_commits"] == (expected_commits if taxonomy else 0), "Wrong SQL membership size")
        require(gauges["consumer.lucene_commits"] == (expected_commits if taxonomy else 0), "Wrong Lucene membership size")
        equality_key = (coordinate, publication)
        identity = (digest, tuple(heads))
        require(equality_key not in membership or membership[equality_key] == identity,
                "Worker variants published different Git/ref membership")
        membership[equality_key] = identity
        observations[key] = row
    require(set(observations) == expected_native, "Incomplete native observation matrix")
    return list(observations.values())


def git(directory, *arguments):
    return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True).strip()


def sources(harness):
    base = Path("jgit-storage-hibernate-benchmarks/src")
    main = base / "main" / PACKAGE
    test = base / "test" / PACKAGE
    names = [main / (name + ".java") for name in
             ("ConsumerBenchmarkFixtures", "BenchmarkConnectionProperties", "JdbcBatchMetricsSessionEventListener")]
    names += [path.relative_to(harness) for path in (harness / main).glob("DatabaseTelemetry*.java")]
    names += [path.relative_to(harness) for path in (harness / test).glob("Consumer*.java")]
    return sorted(names)


def check_revision(harness, library, role):
    actual = git(library, "rev-parse", "HEAD")
    expected = {"release": RELEASE, "main": MAIN, "proposed": git(harness, "rev-parse", "HEAD")}[role]
    require(actual == expected, "Library checkout does not match the approved immutable revision")
    require(not git(library, "diff", "--name-only", "HEAD", "--", *MODULES), "Production module contents were modified")
    return actual


def overlay(harness, library, role):
    check_revision(harness, library, role)
    for relative in sources(harness):
        destination = library / relative
        if destination.resolve() != (harness / relative).resolve():
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(harness / relative, destination)


def record(harness, library, root, role, rtt):
    library_sha = check_revision(harness, library, role)
    harness_sha = git(harness, "rev-parse", "HEAD")
    harness_trees = {module: git(harness, "rev-parse", "HEAD:" + module) for module in MODULES}
    library_trees = {module: git(library, "rev-parse", "HEAD:" + module) for module in MODULES}
    durability = json.loads((root / "postgresql-durability.json").read_text())
    require(all(durability.get(setting) == "on" for setting in DURABILITY_SETTINGS), "PostgreSQL service durability was not on")
    if role == "proposed":
        require(harness_trees == library_trees, "Proposed library differs from its own harness checkout")
    for relative in sources(harness):
        data = (harness / relative).read_bytes()
        require(data == (library / relative).read_bytes(), "Comparison did not use identical harness: " + str(relative))
        target = root / "harness-source" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    files = {}
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.name != "fixture.json":
            data = path.read_bytes()
            if "harness-source" not in path.parts:
                require(b"jdbc:postgresql" not in data and b"fixture-only" not in data, "Connection secret leaked to evidence")
            files[str(path.relative_to(root))] = hashlib.sha256(data).hexdigest()
    metadata = {
        "schemaVersion": 1, "fixtureKind": "consumer-shaped", "fixtureProfile": "publication-followup",
        "role": role, "harnessSha": harness_sha, "librarySha": library_sha,
        "workflowRunId": os.environ["GITHUB_RUN_ID"], "workflowRunAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
        "requestedRttMillis": rtt, "database": "PostgreSQL 17.10", "samplesPerCoordinate": 3,
        "postgresqlDurability": durability,
        "warmupIterations": 1, "forks": 1, "heap": "1 GiB",
        "harnessProductionTreeHashes": harness_trees, "libraryProductionTreeHashes": library_trees,
        "productionModulesIdenticalToHarness": library_trees == harness_trees,
        "cacheState": "provider reopened; Git, database and OS caches may be warm from full verification",
        "telemetryScope": "publication only, before Git/SQL/Lucene verification and provider restart",
        "gcScope": "whole JMH iteration, including verification and provider restart; not publication-only allocation",
        "atomicity": "each workspace publication is independent; no cross-workspace atomicity claim",
        "compatibilityWorkflow": "consumer-compatibility.yml",
        "compatibilityQualification": "requires a separate successful dispatch on harnessSha; historical timings retain their source",
        "filesSha256": files,
    }
    (root / "fixture.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return verify_artifact(root)


def verify_artifact(root):
    fixture = json.loads((root / "fixture.json").read_text())
    require(re.fullmatch("[0-9a-f]{40}", fixture["harnessSha"]), "Invalid harness commit")
    expected_sha = {"release": RELEASE, "main": MAIN, "proposed": fixture["harnessSha"]}[fixture["role"]]
    require(fixture["librarySha"] == expected_sha, "Wrong comparison source")
    for relative, expected in fixture["filesSha256"].items():
        path = root / relative
        require(path.resolve().is_relative_to(root.resolve()), "Artifact path escapes evidence root")
        require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, "Artifact hash mismatch: " + relative)
    rtt = fixture["requestedRttMillis"]
    durability = json.loads((root / "postgresql-durability.json").read_text())
    require(fixture["postgresqlDurability"] == durability
            and all(durability.get(setting) == "on" for setting in DURABILITY_SETTINGS),
            "Manifest and observed PostgreSQL service durability differ")
    require(rtt in (0, 20), "Unexpected RTT")
    calibration = json.loads((root / "taxonomy-rtt-calibration.json").read_text())
    require(calibration["requestedRttMillis"] == rtt and calibration["medianMicros"] >= rtt * 800,
            "RTT calibration does not support the configured proxy delay")
    observations = []
    for shape in (("taxonomy", "audio") if rtt == 0 else ("taxonomy",)):
        raw = json.loads((root / (shape + "-publication.json")).read_text())
        native = json.loads((root / (shape + "-publication-native.json")).read_text())["observations"]
        observations += verify_shape(shape, raw, native)
    return fixture, observations


def compare(root):
    artifacts = [verify_artifact(path.parent) for path in root.rglob("fixture.json")]
    roles = {(fixture["role"], fixture["requestedRttMillis"]) for fixture, _ in artifacts}
    require(len(artifacts) == 6 and roles == set(itertools.product(("release", "main", "proposed"), (0, 20))),
            "Need all six pinned library/RTT artifacts")
    require(len({fixture["harnessSha"] for fixture, _ in artifacts}) == 1, "Mixed harness sources")
    identities = {}
    for fixture, observations in artifacts:
        for row in observations:
            coordinate = row["coordinate"]
            key = (coordinate["operation"], coordinate["coordinate"], coordinate["publication"])
            identity = (row["metadata"]["consumer.membership_sha256"], row["metadata"]["consumer.heads"])
            require(key not in identities or identities[key] == identity, "Library or RTT variant changed the published Git result")
            identities[key] = identity
    summary = {"schemaVersion": 1, "harnessSha": artifacts[0][0]["harnessSha"], "releaseSha": RELEASE,
               "mainSha": MAIN, "artifacts": 6, "observations": sum(len(rows) for _, rows in artifacts),
               "mainProductionModulesIdenticalToProposed": all(fixture["productionModulesIdenticalToHarness"]
                   for fixture, _ in artifacts if fixture["role"] == "main"),
               "identicalGitResultsAcrossLibraryWorkerAndRttVariants": True,
               "timingScope": "three measurements in one fork; no stable latency ranking implied",
               "compatibilityQualification": "pending separate consumer-compatibility run on this exact harnessSha"}
    (root / "comparison.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("overlay", "record", "verify", "compare", "privacy"))
    parser.add_argument("--library", type=Path, default=Path("."))
    parser.add_argument("--role", choices=("release", "main", "proposed"), default="proposed")
    parser.add_argument("--rtt", type=int, default=0)
    parser.add_argument("--root", type=Path, default=Path("target/consumer-publication"))
    parser.add_argument("--reports", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.command == "overlay": overlay(Path.cwd(), args.library, args.role)
    elif args.command == "record":
        fixture, rows = record(Path.cwd(), args.library, args.root, args.role, args.rtt)
        print(json.dumps({"role": fixture["role"], "harnessSha": fixture["harnessSha"], "librarySha": fixture["librarySha"], "observations": len(rows)}))
    elif args.command == "verify":
        fixture, rows = verify_artifact(args.root)
        print(json.dumps({"role": fixture["role"], "observations": len(rows)}))
    elif args.command == "privacy": print(json.dumps({"privacyScannedFiles": privacy([args.root, *args.reports])}))
    else: print(json.dumps(compare(args.root)))
