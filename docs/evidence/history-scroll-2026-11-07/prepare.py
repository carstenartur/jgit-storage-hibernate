"""Verify hosted archive bytes and reproduce their derived comparison tables."""

import argparse
import csv
import hashlib
import importlib.util
import json
import statistics
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

HEAD = "336a65530a16c9a3dd0abfe61b3bed5d5db5b001"
RUN = 42413402130
CHUNKS = [25, 100, 500]
ARTIFACT_FILES = {
    "comparison.md",
    "comparisons.csv",
    "cpu.txt",
    "environment.properties",
    "exports.csv",
    "offsets.csv",
}
EXPORT_COLUMNS = [
    "first_chunk_ns", "api_total_ns", "wall_total_ns", "rows_per_second",
    "chunk_p50_upper_ns", "chunk_p95_upper_ns", "chunk_p99_upper_ns",
    "gc_count", "gc_ms", "heap_before_gc_bytes", "heap_observed_max_bytes",
    "heap_after_gc_bytes",
]


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if not line.startswith("#"))


def same(trials, name):
    values = {r[name] for r in trials}
    assert len(values) == 1, (name, values)
    return values.pop()


def write_csv(path, rows):
    assert rows
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def replay(source, runner, artifact):
    env = properties(source / "environment.properties")
    backend, documents = artifact["backend"], artifact["documents"]
    database = ("PostgreSQL", "17.10") if backend == "postgresql" else ("Microsoft SQL Server", "16.00.4205")
    assert (env["database.product"], env["database.version"]) == database
    assert env["commit"] == HEAD and env["guard.default"] == "10000" and env["guard.benchmark"] == str(documents)
    assert env["heap.init.bytes"] == str(2**30) and env["heap.max.bytes"] == str(2**31)
    assert env["schema.version"] == "2" and env["fixture"] == "history-scroll-projection-v2"
    assert env["cache.condition"] == "first-query-after-factory-reopen-and-warmed-query"
    assert env["heap.retention"] == "whole-process-used-after-explicit-gc"
    assert env["java.version"] == "21.0.13+9-LTS"
    cpu = dict(line.split(":", 1) for line in (source / "cpu.txt").read_text().splitlines() if ":" in line)
    assert int(cpu["CPU(s)"].strip()) == int(env["processors"])
    checked = runner.validate(source, documents, CHUNKS, 3)
    with tempfile.TemporaryDirectory() as generated:
        generated = Path(generated)
        runner.report(generated, documents, 3, CHUNKS, checked)
        for name in ("comparison.md", "comparisons.csv"):
            assert (generated / name).read_bytes() == (source / name).read_bytes()

    raw_exports = runner.rows(source / "exports.csv")
    raw_offsets = runner.rows(source / "offsets.csv")
    return env, cpu, checked, raw_exports, raw_offsets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("runner", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("scroll_cell", args.runner)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    artifacts = json.loads((args.source / "sources.json").read_text())
    expected = {(engine, size) for engine in ("postgresql", "sqlserver")
                for size in (10_000, 100_000, 1_000_000)}
    coordinates = {(a["backend"], a["documents"]) for a in artifacts}
    assert len(coordinates) == len(artifacts)
    assert coordinates == expected
    exports = []
    comparisons = []
    manifest = []
    for artifact in sorted(artifacts, key=lambda a: (a["backend"], a["documents"])):
        assert artifact["head_sha"] == HEAD and artifact["run_id"] == RUN
        assert artifact["branch"] == "benchmark/history-scroll-full-230-nov07"
        assert artifact["job_conclusion"] == "success"
        archive = args.source / f"{artifact['name']}.zip"
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        assert artifact["digest"] == f"sha256:{digest}"
        assert artifact["size_in_bytes"] == archive.stat().st_size
        with tempfile.TemporaryDirectory() as extracted, zipfile.ZipFile(archive) as z:
            source = Path(extracted)
            assert sorted(z.namelist()) == sorted(ARTIFACT_FILES)
            z.extractall(source)
            env, cpu, checked, raw_exports, raw_offsets = replay(source, runner, artifact)

        backend, documents = artifact["backend"], artifact["documents"]
        groups = defaultdict(list)

        for row in raw_exports:
            groups[(row["scenario"], row["cache"], int(row["chunk_size"]))].append(row)
        for (scenario, cache, chunk), trials in sorted(groups.items()):
            assert len(trials) == (3 if cache == "warm" else 1)
            exports.append({
                "backend": backend, "documents": documents, "engine": same(trials, "engine"),
                "scenario": scenario, "cache": cache, "chunk_size": chunk, "trials": len(trials),
                "rows": same(trials, "rows"), "chunks": same(trials, "chunks"),
                **{f"median_{col}": statistics.median(
                    (float if col == "rows_per_second" else int)(r[col]) for r in trials
                ) for col in EXPORT_COLUMNS},
                "max_observed_heap_bytes": max(int(r["heap_observed_max_bytes"]) for r in trials),
                "max_returned_chunk_rows": max(int(r["max_chunk_rows"]) for r in trials),
                "sessions_during": same(trials, "sessions_during"),
                "jdbc_during": same(trials, "jdbc_during"),
                "sessions_after": same(trials, "sessions_after"),
                "jdbc_after": same(trials, "jdbc_after"),
                "transactions_completed": same(trials, "transactions_completed"),
            })
        comparisons.extend({"backend": backend, **row} for row in checked)
        manifest.append({
            **artifact,
            "export_trials": len(raw_exports), "paired_pages": len(raw_offsets),
            "environment": env, "cpu": cpu["Model name"].strip(),
            "artifact_url": f"https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/{RUN}/artifacts/{artifact['id']}",
        })
        print(f"{artifact['name']}: {len(raw_exports)} exports, {len(raw_offsets)} paired pages; digest and report replay verified")
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "export-summary.csv", exports)
    write_csv(args.output / "offset-comparisons.csv", comparisons)
    (args.output / "sources.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
