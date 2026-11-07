#!/usr/bin/env python3
"""Validate and summarize the retained large repository-routing measurements."""

import argparse
from collections import defaultdict
import csv
import hashlib
import importlib.util
import json
import math
from pathlib import Path


FIELDS = (
    "layout,repositories,initial_documents,mode,shards,occupied_shards,documents_per_shard,"
    "files,segments,index_bytes,h2_bytes,peak_sampled_jvm_heap_bytes,"
    "indexing_ms,indexed_docs_per_second,burst_50_ms,burst_500_ms,"
    "text_routed_p50_us,text_routed_p95_us,text_fanout_p50_us,text_fanout_p95_us,"
    "path_routed_p50_us,path_routed_p95_us,path_fanout_p50_us,path_fanout_p95_us,"
    "admin_text_p50_us,admin_text_p95_us,restart_to_first_hit_ms,"
    "purged_docs,routed_purge_and_refresh_ms,mass_rebuild_ms"
).split(",")
VERSIONS = {"stable": "9.12.3", "next": "10.4.0"}
REPOSITORIES = {"single": 1, "uniform": 100, "skewed": 1000}
LAYOUT_NAMES = {"single": "One repository", "uniform": "100 uniform", "skewed": "1,000, 80% hot"}
SIZES = (100_000, 1_000_000)
FILES = {"cpu.json", "filesystem.txt"} | {
    f"{variant}/{name}" for variant in VERSIONS
    for name in ("shards.csv", "environment.properties")
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive_integer(value, context):
    require(value.isascii() and value.isdigit() and int(value) > 0,
            f"{context}: expected a positive integer, found {value!r}")
    return int(value)


def positive_number(value, context):
    try:
        number = float(value)
    except ValueError as failure:
        raise ValueError(f"{context}: expected a finite positive number") from failure
    require(math.isfinite(number) and number > 0,
            f"{context}: expected a finite positive number, found {value!r}")
    return number


def validate_row(row, context, layout, documents, heap):
    require(row["layout"] == layout, f"{context}: unexpected layout")
    require(positive_integer(row["repositories"], context) == REPOSITORIES[layout],
            f"{context}: repository count does not match the matrix")
    require(positive_integer(row["initial_documents"], context) == documents,
            f"{context}: document count does not match the matrix")
    shards = positive_integer(row["shards"], context)
    routed = row["mode"] == "routed"
    require((routed and shards in (1, 2, 4, 8)) or (row["mode"] == "baseline" and shards == 1),
            f"{context}: invalid routing mode or shard count")
    total = documents + 550
    counts = row["documents_per_shard"].split(";")
    require(len(counts) == shards and all(value.isascii() and value.isdigit() for value in counts),
            f"{context}: invalid documents_per_shard")
    counts = list(map(int, counts))
    require(sum(counts) == total, f"{context}: shard document sum does not match the fixture")
    occupied = positive_integer(row["occupied_shards"], context)
    require(occupied == sum(count > 0 for count in counts), f"{context}: incorrect occupied_shards")
    if layout == "single":
        require(occupied == 1, f"{context}: one repository was split across shards")
    for field in ("files", "segments", "index_bytes", "h2_bytes", "peak_sampled_jvm_heap_bytes"):
        positive_integer(row[field], f"{context}: {field}")
    require(int(row["peak_sampled_jvm_heap_bytes"]) <= heap,
            f"{context}: sampled heap exceeds the configured maximum")
    for field in ("indexing_ms", "indexed_docs_per_second", "burst_50_ms", "burst_500_ms",
                  "restart_to_first_hit_ms", "mass_rebuild_ms"):
        positive_number(row[field], f"{context}: {field}")
    require(math.isclose(float(row["indexed_docs_per_second"]),
                         documents * 1000 / float(row["indexing_ms"]), abs_tol=0.002),
            f"{context}: indexing throughput does not match duration")
    for prefix in ("text_routed", "text_fanout", "path_routed", "path_fanout", "admin_text"):
        pair = [row[f"{prefix}_{percentile}_us"] for percentile in ("p50", "p95")]
        if not routed and prefix.endswith("routed"):
            require(pair == ["", ""], f"{context}: routed timings on an unrouted baseline")
        else:
            p50, p95 = (positive_number(value, f"{context}: {prefix}") for value in pair)
            require(p50 <= p95, f"{context}: {prefix} p50 exceeds p95")
    if routed:
        purged = positive_integer(row["purged_docs"], f"{context}: purged_docs")
        expected = {"single": total, "uniform": math.ceil(total / 100),
                    "skewed": total - math.ceil(total / 5)}[layout]
        require(purged == expected, f"{context}: purged_docs does not match the target repository")
        positive_number(row["routed_purge_and_refresh_ms"], context)
    else:
        require(row["purged_docs"] == row["routed_purge_and_refresh_ms"] == "",
                f"{context}: routed purge fields on an unrouted baseline")


def validate_and_load(root):
    """Return every observation after checking its retained source and matrix."""
    root = Path(root)
    manifest = json.loads((root / "provenance.json").read_text(encoding="utf-8"))
    expected = {
        f"search-routing-{layout}-{size}-{1000 if layout == 'skewed' else 100}": (layout, size)
        for layout in REPOSITORIES for size in SIZES
    }
    artifacts = manifest["artifacts"]
    require(len(artifacts) == len(expected) and {item["name"] for item in artifacts} == set(expected),
            "Incomplete or duplicate artifact matrix")
    observations = []
    for artifact in sorted(artifacts, key=lambda item: item["name"]):
        name = artifact["name"]
        layout, documents = expected[name]
        source = root / "raw" / name
        require(set(artifact["files"]) == FILES, f"{name}: incomplete retained files")
        for relative, evidence in artifact["files"].items():
            raw = (source / relative).read_bytes()
            require(len(raw) == evidence["bytes"], f"{name}/{relative}: size mismatch")
            require(hashlib.sha256(raw).hexdigest() == evidence["sha256"],
                    f"{name}/{relative}: digest mismatch")
        for variant, version in VERSIONS.items():
            environment = dict(line.split("=", 1) for line in
                               (source / variant / "environment.properties").read_text().splitlines()
                               if line and not line.startswith("#"))
            expected_environment = {
                "source": manifest["source_commit"], "lucene": version,
                "database": "H2 local file", "synchronization": "sync", "index.batch": "250",
                "query.samples": "200", "query.warmup": "80", "jvm.available.processors": "4",
                "jvm.heap.max.bytes": "1073741824", "java": "21.0.13+9-LTS", "os": "Linux amd64",
            }
            for key, value in expected_environment.items():
                require(environment.get(key) == value, f"{name}/{variant}: unexpected {key}")
            with (source / variant / "shards.csv").open(encoding="utf-8", newline="") as csvfile:
                reader = csv.reader(csvfile, strict=True)
                require(next(reader, None) == FIELDS, f"{name}/{variant}: unexpected CSV columns")
                rows = []
                for number, values in enumerate(reader, start=2):
                    context = f"{name}/{variant}/shards.csv:{number}"
                    require(len(values) == len(FIELDS), f"{context}: malformed row")
                    row = dict(zip(FIELDS, values))
                    validate_row(row, context, layout, documents, int(environment["jvm.heap.max.bytes"]))
                    rows.append(row)
            coordinates = {(row["mode"], int(row["shards"])) for row in rows}
            required = {("baseline", 1)} | {
                ("routed", count) for count in ((1, 2, 4, 8) if documents == 100_000 else (1, 8))
            }
            require(coordinates == required and len(rows) == len(required),
                    f"{name}/{variant}: incomplete or duplicate coordinate matrix")
            observations.extend({"lucene_variant": variant, "lucene_version": version,
                                 "artifact": name, **row} for row in rows)
    return observations


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fields, lineterminator="\n", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def endpoint_comparisons(observations):
    comparisons = []
    for variant, version in VERSIONS.items():
        for size in SIZES:
            for layout in REPOSITORIES:
                group = {(row["mode"], int(row["shards"])): row for row in observations
                         if row["lucene_variant"] == variant and row["layout"] == layout
                         and int(row["initial_documents"]) == size}
                baseline, one, eight = (group[key] for key in (("baseline", 1), ("routed", 1),
                                                              ("routed", 8)))
                comparison = {"lucene_variant": variant, "lucene_version": version,
                              "layout": layout, "repositories": REPOSITORIES[layout],
                              "initial_documents": size}
                for key, row, field in (
                    ("baseline_text_p95_ms", baseline, "text_fanout_p95_us"),
                    ("routed_1_text_p95_ms", one, "text_routed_p95_us"),
                    ("routed_8_text_p95_ms", eight, "text_routed_p95_us"),
                    ("fanout_8_text_p95_ms", eight, "text_fanout_p95_us"),
                    ("routed_1_path_p95_ms", one, "path_routed_p95_us"),
                    ("routed_8_path_p95_ms", eight, "path_routed_p95_us"),
                    ("fanout_8_path_p95_ms", eight, "path_fanout_p95_us"),
                    ("admin_1_p95_ms", one, "admin_text_p95_us"),
                    ("admin_8_p95_ms", eight, "admin_text_p95_us"),
                ):
                    comparison[key] = f"{float(row[field]) / 1000:.3f}"
                comparison["routed_8_to_fanout_8_text_ratio"] = (
                    f"{float(eight['text_routed_p95_us']) / float(eight['text_fanout_p95_us']):.4f}")
                for field in ("indexed_docs_per_second", "files", "segments", "index_bytes", "h2_bytes",
                              "peak_sampled_jvm_heap_bytes", "restart_to_first_hit_ms",
                              "routed_purge_and_refresh_ms", "mass_rebuild_ms"):
                    for shard, row in ((1, one), (8, eight)):
                        comparison[f"{field}_{shard}"] = row[field]
                comparison["max_shard_documents_8"] = max(map(int, eight["documents_per_shard"].split(";")))
                comparison["total_documents"] = size + 550
                comparisons.append(comparison)
    return comparisons


def markdown_tables(comparisons):
    lines = ["# Large routing observations", "", "Generated from the verified raw CSV files. "
             "Times are point estimates; p95 values are from 200 warmed queries per mode.", ""]
    for variant, version in VERSIONS.items():
        rows = [row for row in comparisons if row["lucene_variant"] == variant]
        lines += [f"## {variant}: Lucene {version}", "", "### Repository text p95", "",
                  "| Initial documents | Repository layout | Unrouted baseline (ms) | Routed 1 (ms) | Routed 8 (ms) | Fan-out 8 (ms) |",
                  "| ---: | --- | ---: | ---: | ---: | ---: |"]
        for row in rows:
            values = [f"{row['initial_documents']:,}", LAYOUT_NAMES[row["layout"]]] + [row[key] for key in
                      ("baseline_text_p95_ms", "routed_1_text_p95_ms", "routed_8_text_p95_ms", "fanout_8_text_p95_ms")]
            lines.append("| " + " | ".join(values) + " |")
        lines += ["", "### Routed index and restart costs", "",
                  "Each pair is 1 / 8 shards. Index files and bytes are measured after close; "
                  "factory reopen includes the readiness check and first hit in the same JVM.", "",
                  "| Initial documents | Repository layout | Initial docs/s | Files | Index MiB | Factory reopen (s) | Full rebuild (s) |",
                  "| ---: | --- | ---: | ---: | ---: | ---: | ---: |"]
        for row in rows:
            values = [f"{row['initial_documents']:,}", LAYOUT_NAMES[row["layout"]]]
            for key, scale, precision in (("indexed_docs_per_second", 1, 0), ("files", 1, 0),
                                          ("index_bytes", 1024 ** 2, 2),
                                          ("restart_to_first_hit_ms", 1000, 2), ("mass_rebuild_ms", 1000, 2)):
                values.append(" / ".join(f"{float(row[f'{key}_{shard}']) / scale:,.{precision}f}"
                                         for shard in (1, 8)))
            lines.append("| " + " | ".join(values) + " |")
        lines.append("")
    return "\n".join(lines)


def summarize(root, output):
    observations = validate_and_load(root)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "observations.csv", ["lucene_variant", "lucene_version", "artifact", *FIELDS], observations)
    comparisons = endpoint_comparisons(observations)
    write_csv(output / "endpoint-comparisons.csv", list(comparisons[0]), comparisons)
    (output / "tables.md").write_text(markdown_tables(comparisons), encoding="utf-8")
    spec = importlib.util.spec_from_file_location("routing_plot", Path(__file__).with_name("plot-search-routing.py"))
    plot = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plot)
    for variant, version in VERSIONS.items():
        for size in SIZES:
            directory = output / f"{variant}-{size}"
            directory.mkdir(exist_ok=True)
            groups = defaultdict(list)
            for layout in REPOSITORIES:
                groups[layout] = [row for row in observations if row["lucene_variant"] == variant
                                  and int(row["initial_documents"]) == size and row["layout"] == layout]
            write_csv(directory / "shards.csv", FIELDS, [row for rows in groups.values() for row in rows])
            subtitle = f"H2 + local Lucene {version} · {size:,} initial documents · one JVM / 1 GiB"
            charts = {
                "query-latency.svg": plot.render(groups, "Repository text query latency (p95)", subtitle,
                                                 (("Routed", "text_routed_p95_us"),
                                                  ("Fan-out", "text_fanout_p95_us")), "µs"),
                "indexing-throughput.svg": plot.render(groups, "Initial projection indexing throughput", subtitle,
                                                       (("Repository routing", "indexed_docs_per_second"),), "docs/s", True),
            }
            for filename, contents in charts.items():
                (directory / filename).write_text(contents, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    summarize(args.evidence, args.output or args.evidence / "derived")


if __name__ == "__main__":
    main()
