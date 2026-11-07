#!/usr/bin/env python3
"""Reproduce the hosted Search runtime evidence without running Java benchmarks."""

import argparse
import csv
import gzip
import hashlib
import html
import json
import math
from pathlib import Path
import re
import statistics


ROOT = Path(__file__).resolve().parent
OPERATIONS = ("incrementalBurstSubmission", "incrementalBurstReady",
              "steadyQueriesDuringBurst", "projectionRebuildReady")
SMOKE = ("reference", "sync-async-r500", "sync-read-sync-r0", "sync-sync-r0",
         "sync-write-sync-r500", "writer-ram64-t4", "batch-250")
FULL = ("reference",) + tuple(f"sync-{mode}-r{refresh}"
                              for mode in ("async", "write-sync", "read-sync", "sync")
                              for refresh in (0, 100, 500, 1000)) + tuple(
    f"writer-ram{ram}-t{threads}" for ram in (16, 32, 64, 128, 256) for threads in (1, 2, 4, 8)
) + tuple(f"batch-{batch}" for batch in (1, 50, 250, 500))
CELLS = {
    "1000-500-normal-full": (1000, 500, "normal", "full", FULL, 3, 1),
    "10000-50-normal-smoke": (10000, 50, "normal", "smoke", SMOKE, 2, 0),
    "10000-500-normal-smoke": (10000, 500, "normal", "smoke", SMOKE, 2, 0),
    "10000-500-constrained-smoke": (10000, 500, "constrained", "smoke", SMOKE, 2, 0),
}
COUNTERS = ("indexedCommits", "preparedStatements", "transactions", "queryExecutions",
            "visibilityPolls", "visibilityWaitMicros", "concurrentQueries", "queryP50Micros",
            "queryP95Micros", "queryP99Micros", "resultCount", "luceneCommitGenerations",
            "luceneCommittedSegments", "luceneCommittedDocuments", "luceneCommittedBytes",
            "peakHeapPoolBytes")
GC_FIELDS = ("gc.alloc.rate", "gc.alloc.rate.norm", "gc.count")
RETAINED = {"jmh-result.json.gz", "environment.properties", "cpu.json", "comparison.json.gz",
            "recovery.txt", "benchmark.txt"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def properties(text):
    return {key: re.sub(r"\\(.)", r"\1", value)
            for line in text.splitlines() if line and not line.startswith("#")
            for key, separator, value in (line.partition("="),) if separator}


def raw_samples(metric, count, context, positive=False, sparse=False):
    raw = metric.get("rawData")
    require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], list)
            and (0 < len(raw[0]) <= count if sparse else len(raw[0]) == count),
            f"{context}: expected one fork with {'up to ' if sparse else ''}{count} raw shots")
    values = [float(value) for value in raw[0]]
    require(all(math.isfinite(value) and (value > 0 if positive else value >= 0) for value in values),
            f"{context}: invalid raw measurement")
    return values


def verify_files(root, artifact):
    cell = artifact["cell"]
    require(set(artifact["files"]) == RETAINED, f"{cell}: incomplete retained files")
    contents = {}
    for filename, expected in artifact["files"].items():
        stored = (root / "raw" / cell / filename).read_bytes()
        require(len(stored) == expected["bytes"], f"{cell}/{filename}: size mismatch")
        require(hashlib.sha256(stored).hexdigest() == expected["sha256"],
                f"{cell}/{filename}: digest mismatch")
        raw = gzip.decompress(stored) if filename.endswith(".gz") else stored
        require(len(raw) == expected["original_bytes"], f"{cell}/{filename}: original size mismatch")
        require(hashlib.sha256(raw).hexdigest() == expected["original_sha256"],
                f"{cell}/{filename}: original digest mismatch")
        contents[filename] = raw
    return contents


def load_data(root, allow_partial=False):
    """Read verified JMH invocations and reject incomplete or inconsistent inputs."""
    root = Path(root)
    manifest = json.loads((root / "provenance.json").read_text())
    artifacts = manifest["artifacts"]
    cells = {artifact["cell"] for artifact in artifacts}
    require(cells and cells <= set(CELLS) and len(cells) == len(artifacts), "Unexpected or duplicate cell matrix")
    require(allow_partial or cells == set(CELLS), "Incomplete four-cell runtime matrix")
    observations = []
    for artifact in sorted(artifacts, key=lambda item: list(CELLS).index(item["cell"])):
        cell = artifact["cell"]
        commits, burst, resources, settings, scenarios, shots, warmups = CELLS[cell]
        job_name = f"PostgreSQL + Lucene {commits}-{burst} ({resources}, {settings})"
        jobs = [job for job in manifest["jobs"] if job["name"] == job_name]
        require(len(jobs) == 1 and jobs[0]["status"] == "completed" and jobs[0]["conclusion"] == "success",
                f"{cell}: evidence requires a successful completed job")
        privacy = [step for step in jobs[0]["steps"] if step["name"] == "Check Search runtime evidence for connection details"]
        require(len(privacy) == 1 and privacy[0]["conclusion"] == "success", f"{cell}: privacy gate not successful")
        raw = verify_files(root, artifact)
        environment = properties(raw["environment.properties"].decode())
        expected = {"commit": manifest["source_commit"], "workload.commits": str(commits),
                    "workload.burst.commits": str(burst), "resources": resources, "matrix": settings,
                    "measurement.iterations": str(shots), "warmup.iterations": str(warmups),
                    "runtime.scenarios": ",".join(scenarios), "hibernate.search.version": "8.4.0.Final",
                    "lucene.version": "9.12.3", "database.version": "17.10", "jdbc.driver.version": "42.7.14"}
        expected_args = (["-Xms256m", "-Xmx512m", "-XX:ActiveProcessorCount=1"] if resources == "constrained"
                         else ["-Xms768m", "-Xmx1024m", "-XX:ActiveProcessorCount=4"])
        expected["fork.resource.arguments"] = " ".join(expected_args)
        for key, value in expected.items():
            require(environment.get(key) == value, f"{cell}: unexpected environment {key}")
        affinity = environment["cpu.affinity"].split(",")
        cpus = sum(int(part.split("-")[-1]) - int(part.split("-")[0]) + 1 for part in affinity)
        require(cpus == (1 if resources == "constrained" else 4), f"{cell}: incorrect CPU affinity")
        for filename, count in (("recovery.txt", 7), ("benchmark.txt", 1)):
            require(f"Tests run: {count}, Failures: 0, Errors: 0, Skipped: 0" in raw[filename].decode(),
                    f"{cell}: unsuccessful {filename}")
        results = json.loads(raw["jmh-result.json.gz"])
        coordinates = set()
        for result in results:
            method = result["benchmark"].rsplit(".", 1)[-1]
            params = result["params"]
            scenario = params["runtimeScenario"]
            key = (scenario, method)
            context = f"{cell}/{scenario}/{method}"
            require(key not in coordinates and method in OPERATIONS and scenario in scenarios,
                    f"{context}: duplicate or unexpected coordinate")
            coordinates.add(key)
            require(params["commitCount"] == str(commits) and params["burstCount"] == str(burst),
                    f"{context}: incorrect workload")
            require(result["mode"] == "ss" and result["forks"] == result["threads"] == 1,
                    f"{context}: unexpected JMH execution mode")
            require(result["measurementIterations"] == shots and result["warmupIterations"] == warmups,
                    f"{context}: unexpected iteration count")
            require(all(arg in result["jvmArgs"] for arg in expected_args), f"{context}: incorrect JVM budget")
            require(result["primaryMetric"]["scoreUnit"] == "ms/op", f"{context}: incorrect primary unit")
            primary = raw_samples(result["primaryMetric"], shots, context, positive=True)
            require(math.isclose(statistics.fmean(primary), float(result["primaryMetric"]["score"]), rel_tol=1e-9),
                    f"{context}: primary score is inconsistent with raw shots")
            metrics = result["secondaryMetrics"]
            require(set(COUNTERS + GC_FIELDS) <= set(metrics), f"{context}: missing secondary metric")
            secondary = {field: raw_samples(metrics[field], shots, f"{context}/{field}")
                         for field in COUNTERS + GC_FIELDS}
            # JMH omits gc.time observations for some invocations without reported GC time.
            # Keep the reported values separate; their positions cannot be joined to raw shots.
            secondary["gc.time"] = (raw_samples(metrics["gc.time"], shots, f"{context}/gc.time", sparse=True)
                                    if "gc.time" in metrics else [])
            for field in COUNTERS:
                require(all(value.is_integer() for value in secondary[field]), f"{context}: noninteger {field}")
            expected_indexed = commits if method == "projectionRebuildReady" else burst
            for index in range(shots):
                value = lambda field: secondary[field][index]
                require(value("luceneCommittedDocuments") == commits and value("indexedCommits") == expected_indexed,
                        f"{context}: incorrect committed/indexed document count")
                require(all(value(field) > 0 for field in ("luceneCommitGenerations", "luceneCommittedSegments",
                                                           "luceneCommittedBytes", "peakHeapPoolBytes", "transactions")),
                        f"{context}: empty resource observation")
                if method == "incrementalBurstSubmission":
                    require(value("visibilityWaitMicros") == value("visibilityPolls") == value("resultCount") == 0,
                            f"{context}: submission includes a measured visibility wait")
                else:
                    require(value("resultCount") == expected_indexed and value("visibilityPolls") > 0,
                            f"{context}: incomplete visibility verification")
                    require(value("visibilityWaitMicros") <= primary[index] * 1000,
                            f"{context}: visibility wait exceeds the operation")
                if method == "steadyQueriesDuringBurst":
                    require(value("concurrentQueries") >= 5 and
                            0 < value("queryP50Micros") <= value("queryP95Micros") <= value("queryP99Micros"),
                            f"{context}: invalid query distribution")
                else:
                    require(all(value(field) == 0 for field in ("concurrentQueries", "queryP50Micros", "queryP95Micros", "queryP99Micros")),
                            f"{context}: query distribution on a non-query operation")
            observations.append({"cell": cell, "scenario": scenario, "operation": method,
                                 "commits": commits, "burst": burst, "resources": resources,
                                 "settings": settings, "primary": primary, "secondary": secondary})
        require(coordinates == {(scenario, method) for scenario in scenarios for method in OPERATIONS},
                f"{cell}: incomplete scenario/operation matrix")
    return observations


def statistics_fields(prefix, values):
    return {f"{prefix}_mean": statistics.fmean(values), f"{prefix}_min": min(values),
            f"{prefix}_max": max(values)}


def summarize(observations):
    by_key = {(row["cell"], row["scenario"], row["operation"]): row for row in observations}
    rows = []
    for cell, (commits, burst, resources, settings, scenarios, shots, _) in CELLS.items():
        if not any(row["cell"] == cell for row in observations):
            continue
        for scenario in scenarios:
            operations = {operation: by_key[cell, scenario, operation] for operation in OPERATIONS}
            row = {"cell": cell, "scenario": scenario, "commits": commits, "burst": burst,
                   "resources": resources, "settings": settings, "shots": shots}
            for prefix, operation in zip(("submission_ms", "ready_ms", "query_operation_ms", "rebuild_ms"), OPERATIONS):
                row.update(statistics_fields(prefix, operations[operation]["primary"]))
            row["submission_commits_per_second"] = burst * 1000 / row["submission_ms_mean"]
            row["submission_rate_min"] = burst * 1000 / row["submission_ms_max"]
            row["submission_rate_max"] = burst * 1000 / row["submission_ms_min"]
            row["rebuild_commits_per_second"] = commits * 1000 / row["rebuild_ms_mean"]
            ready = operations["incrementalBurstReady"]["secondary"]
            queries = operations["steadyQueriesDuringBurst"]["secondary"]
            row.update(statistics_fields("visibility_wait_ms", [value / 1000 for value in ready["visibilityWaitMicros"]]))
            for percentile in ("50", "95", "99"):
                row.update(statistics_fields(f"query_p{percentile}_ms",
                                             [value / 1000 for value in queries[f"queryP{percentile}Micros"]]))
            row["query_samples_min"] = min(queries["concurrentQueries"])
            row["query_samples_max"] = max(queries["concurrentQueries"])
            rows.append(row)
    return rows


def write_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows({key: f"{value:.6f}" if isinstance(value, float) else value
                         for key, value in row.items()} for row in rows)


def settings_row(scenario):
    row = {"scenario": scenario, "strategy": "write-sync", "refresh_ms": 0,
           "writer_ram_mib": "default", "backend_threads": "default", "projection_jdbc_batch": 50}
    if scenario.startswith("sync-"):
        mode, refresh = scenario[5:].rsplit("-r", 1)
        row.update(strategy=mode, refresh_ms=int(refresh))
    elif scenario.startswith("writer-"):
        ram, threads = scenario.removeprefix("writer-ram").split("-t")
        row.update(writer_ram_mib=int(ram), backend_threads=int(threads))
    elif scenario.startswith("batch-"):
        row["projection_jdbc_batch"] = int(scenario.removeprefix("batch-"))
    return row


def tables(rows):
    lines = ["# Hosted Search runtime observations", "",
             "Generated from the verified JMH raw shots. Rates divide work by mean elapsed time. "
             "Query percentiles are means of per-invocation percentiles; they are not pooled "
             "percentiles or service-level estimates.", ""]
    for cell in CELLS:
        selected = [row for row in rows if row["cell"] == cell]
        if not selected:
            continue
        lines += [f"## {cell}", "", f"{selected[0]['shots']} measured shots per operation/scenario, one fork. "
                  "Submission and ready measurements use separate invocations. "
                  "The wait column is observed after submission inside the ready invocation.", "",
                  "| Scenario | Submit (commits/s) | Burst ready (ms) | Wait after submit (ms) | Query p50 / p95 / p99 (ms) | Query samples per shot | Rebuild (commits/s) |",
                  "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for row in selected:
            quantiles = " / ".join(f"{row[f'query_p{p}_ms_mean']:.2f}" for p in (50, 95, 99))
            sample_range = f"{row['query_samples_min']:.0f}–{row['query_samples_max']:.0f}"
            lines.append(f"| `{row['scenario']}` | {row['submission_commits_per_second']:,.1f} | "
                         f"{row['ready_ms_mean']:,.2f} | {row['visibility_wait_ms_mean']:,.2f} | "
                         f"{quantiles} | {sample_range} | {row['rebuild_commits_per_second']:,.1f} |")
        lines.append("")
    return "\n".join(lines)


def short_label(scenario):
    if scenario == "reference":
        return "write-sync 0 ms (ref)"
    if scenario.startswith("sync-"):
        return scenario[5:].replace("-r", " / ") + " ms"
    if scenario.startswith("writer-"):
        return scenario.removeprefix("writer-").replace("ram", "RAM ").replace("-t", " / T")
    return "batch " + scenario.removeprefix("batch-")


def chart(rows, title):
    width, height = 1410, 180 + 32 * len(rows)
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
           f'viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}">',
           f'<rect width="{width}" height="{height}" rx="12" fill="#f7f9fc"/>',
           '<g font-family="system-ui, sans-serif" fill="#24324b">']

    def text(x, y, value, size=12, anchor="start", weight=400):
        svg.append(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" text-anchor="{anchor}" '
                   f'font-weight="{weight}">{html.escape(str(value))}</text>')

    text(20, 29, title, 20, weight=700)
    text(20, 51, f"{rows[0]['shots']} shots per operation · whiskers: observed min–max, not confidence intervals · each panel has its own scale", 12)
    panels = (
        ("Submission rate (higher is better)", "commits/s", "submission_commits_per_second", "submission_rate_min", "submission_rate_max", "#007c83"),
        ("Visibility wait after submission", "ms", "visibility_wait_ms_mean", "visibility_wait_ms_min", "visibility_wait_ms_max", "#a04425"),
        ("Mean concurrent query p95", "ms", "query_p95_ms_mean", "query_p95_ms_min", "query_p95_ms_max", "#526780"),
    )
    top, bottom = 108, 108 + len(rows) * 32
    for panel, (label, unit, mean, minimum, maximum, color) in enumerate(panels):
        left = 20 + panel * 470
        start, end = left + 153, left + 442
        ceiling = max(row[maximum] for row in rows)
        scale = 10 ** math.floor(math.log10(ceiling))
        ceiling = math.ceil(ceiling / scale) * scale
        text(left, 85, label, 15, weight=700)
        text(end, 101, unit, 11, "end")
        for tick in range(4):
            value = ceiling * tick / 3
            x = start + (end - start) * tick / 3
            svg.append(f'<line x1="{x:.1f}" x2="{x:.1f}" y1="{top}" y2="{bottom}" stroke="#d6deec"/>')
            text(x, bottom + 17, f"{value:,.1f}" if ceiling < 10 else f"{value:,.0f}", 10, "middle")
        for index, row in enumerate(rows):
            y = top + 16 + 32 * index
            text(start - 8, y + 4, short_label(row["scenario"]), 11, "end")
            x = start + row[mean] / ceiling * (end - start)
            lo = start + row[minimum] / ceiling * (end - start)
            hi = start + row[maximum] / ceiling * (end - start)
            svg.append(f'<rect x="{start:.1f}" y="{y - 9}" width="{x - start:.1f}" height="18" rx="2" fill="{color}">'
                       f'<title>{row["scenario"]}: {row[mean]:.3f} {unit}; observed {row[minimum]:.3f}–{row[maximum]:.3f}</title></rect>')
            svg.append(f'<path d="M {lo:.1f} {y} H {hi:.1f} M {lo:.1f} {y - 4} V {y + 4} M {hi:.1f} {y - 4} V {y + 4}" '
                       'stroke="#24324b" stroke-width="1.2" fill="none"/>')
    text(20, height - 32, "Query p95 is computed within each invocation; query sample counts and all raw shots are retained separately.", 11)
    text(20, height - 12, "Acknowledgment: write-sync = durable, refresh-dependent; async = submitted only; read-sync = visible, may be uncommitted; sync = durable + visible.", 12, weight=500)
    return "\n".join([*svg, "</g>", "</svg>", ""])


def reproduce(root, output, allow_partial=False):
    observations = load_data(root, allow_partial)
    output.mkdir(parents=True, exist_ok=True)
    shots, resources, gc_times = [], [], []
    for row in observations:
        for shot, elapsed in enumerate(row["primary"], start=1):
            shots.append({"cell": row["cell"], "scenario": row["scenario"], "operation": row["operation"],
                          "shot": shot, "elapsed_ms": elapsed,
                          **{field: row["secondary"][field][shot - 1] for field in COUNTERS + GC_FIELDS}})
        for number, value in enumerate(row["secondary"]["gc.time"], start=1):
            gc_times.append({"cell": row["cell"], "scenario": row["scenario"], "operation": row["operation"],
                             "reported_observation": number, "gc_time_ms": value,
                             "reported_gc_time_values": len(row["secondary"]["gc.time"]),
                             "measured_shots": len(row["primary"])})
        if row["operation"] in ("incrementalBurstReady", "projectionRebuildReady"):
            resource = {"cell": row["cell"], "scenario": row["scenario"], "operation": row["operation"]}
            for field in COUNTERS + GC_FIELDS:
                resource.update(statistics_fields(field, row["secondary"][field]))
            resources.append(resource)
    summaries = summarize(observations)
    write_csv(output / "shots.csv", shots)
    write_csv(output / "summary.csv", summaries)
    write_csv(output / "resource-observations.csv", resources)
    write_csv(output / "gc-time-observations.csv", gc_times)
    selected_scenarios = {row["scenario"] for row in observations}
    write_csv(output / "scenario-settings.csv", [settings_row(scenario) for scenario in FULL if scenario in selected_scenarios])
    (output / "tables.md").write_text(tables(summaries), encoding="utf-8")
    for cell in CELLS:
        selected = [row for row in summaries if row["cell"] == cell]
        if not selected:
            continue
        directory = output / cell
        directory.mkdir(exist_ok=True)
        by_scenario = {row["scenario"]: row for row in selected}
        groups = {"coordination": [by_scenario[scenario] for scenario in SMOKE[:5]]}
        if cell.endswith("full"):
            groups["synchronization"] = [row for row in selected if row["scenario"] == "reference" or row["scenario"].startswith("sync-")]
            groups["writer"] = [row for row in selected if row["scenario"].startswith("writer-")]
            groups["batch"] = [row for row in selected if row["scenario"] == "reference" or row["scenario"].startswith("batch-")]
        for family, rows in groups.items():
            title = f"Search runtime: {rows[0]['commits']:,} commits / {rows[0]['burst']} burst / {rows[0]['resources']} / {family}"
            (directory / f"{family}.svg").write_text(chart(rows, title), encoding="utf-8")
    print(f"Verified {len(observations)} scenario/operation records and {len(shots)} measured shots; "
          f"wrote {len(summaries)} scenario summaries")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "derived")
    parser.add_argument("--allow-partial", action="store_true", help="For staging only; final report requires all four successful cells")
    args = parser.parse_args()
    reproduce(ROOT, args.output, args.allow_partial)


if __name__ == "__main__":
    main()
