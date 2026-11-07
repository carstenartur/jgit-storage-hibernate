"""Validate one history scroll benchmark cell and summarize its observed offsets."""

import argparse
import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path


SCENARIOS = {
    "repository": "orm",
    "indexed-export": "search",
    "path": "search",
    "author": "orm",
    "time": "orm",
    "combined": "search",
    "rare": "search",
    "empty": "search",
}
LATENCIES = ("offset_page_ns", "scroll_chunk_ns", "scroll_prefix_ns")
EXPORT_COLUMNS = (
    "engine,scenario,cache,documents,chunk_size,repeat,rows,chunks,first_chunk_ns,api_total_ns,"
    "wall_total_ns,rows_per_second,chunk_p50_upper_ns,chunk_p95_upper_ns,chunk_p99_upper_ns,"
    "gc_count,gc_ms,heap_before_gc_bytes,heap_observed_max_bytes,heap_after_bytes,"
    "heap_after_gc_bytes,max_chunk_rows,sessions_before,sessions_during,sessions_after,"
    "jdbc_before,jdbc_during,jdbc_after,transactions_before,transactions_during,"
    "transactions_after,transactions_completed"
).split(",")
OFFSET_COLUMNS = (
    "engine,scenario,cache,documents,chunk_size,repeat,offset,rows,scroll_chunk_ns,"
    "scroll_prefix_ns,offset_page_ns,first_object_id,last_object_id"
).split(",")
TEXT_COLUMNS = {"engine", "scenario", "cache", "first_object_id", "last_object_id"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def rows(path, columns=None):
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream, strict=True)
        try:
            header = reader.fieldnames
            require(header and all(header) and len(header) == len(set(header)),
                    f"Invalid CSV header in {path.name}")
            if columns is not None:
                missing, extra = set(columns) - set(header), set(header) - set(columns)
                require(not missing and not extra,
                        f"Invalid CSV columns in {path.name}: missing {sorted(missing)}, "
                        f"unexpected {sorted(extra)}")
            result = []
            for row in reader:
                require(None not in row and all(value is not None for value in row.values()),
                        f"Malformed CSV row {reader.line_num} in {path.name}")
                result.append(row)
            return result
        except csv.Error as error:
            raise ValueError(f"Invalid CSV in {path.name}: {error}") from error


def measurements(path, columns):
    result = rows(path, columns)
    for number, row in enumerate(result, 2):
        for column in columns:
            if column in TEXT_COLUMNS:
                continue
            parse = float if column == "rows_per_second" else int
            check = f"{column} in {path.name} row {number}"
            try:
                value = parse(row[column])
            except ValueError as error:
                raise ValueError(f"Invalid numeric measurement {check}") from error
            require(value >= 0 and (parse is int or math.isfinite(value)),
                    f"Invalid numeric measurement {check}")
            row[column] = value
    return result


def coordinate(row):
    return row["scenario"], int(row["chunk_size"]), int(row["repeat"])


def sample_offsets(result_rows, chunk):
    if result_rows == 0:
        return {0}
    guard = 10_000 // chunk * chunk
    return {
        offset for offset in (
            0, chunk, result_rows // 10 // chunk * chunk, result_rows // 2 // chunk * chunk,
            (result_rows - 1) // chunk * chunk, max(0, guard - chunk), guard, guard + chunk,
        ) if offset < result_rows
    }


def validate(root, documents, chunks, repetitions):
    warm = {}
    reopened = {}
    for row in measurements(root / "exports.csv", EXPORT_COLUMNS):
        scenario, chunk, repeat = coordinate(row)
        count = int(row["rows"])
        check = f"{scenario}/{chunk}/{repeat}/{row['cache']}"
        require(int(row["documents"]) == documents and SCENARIOS.get(scenario) == row["engine"]
                and chunk in chunks and chunk > 0 and 1 <= repeat <= repetitions,
                f"Wrong export fixture at {check}")
        require(0 <= count <= documents and (count == 0) == (scenario == "empty"),
                f"Unexpected result count at {check}")
        if scenario in ("repository", "indexed-export"):
            require(count == documents, f"Missing full export results at {check}")
        require(int(row["chunks"]) == (count + chunk - 1) // chunk
                and int(row["max_chunk_rows"]) == min(chunk, count),
                f"Chunk budget exceeded at {check}")
        quantiles = [int(row[f"chunk_p{p}_upper_ns"]) for p in (50, 95, 99)]
        require(quantiles == sorted(quantiles)
                and (quantiles[0] > 0 if count else quantiles == [0, 0, 0]),
                f"Invalid chunk latencies at {check}")
        first, api, wall = (row[name] for name in ("first_chunk_ns", "api_total_ns", "wall_total_ns"))
        require(first <= api <= wall and api > 0 and (first > 0) == (count > 0),
                f"Invalid export latencies at {check}")
        require(math.isclose(row["rows_per_second"], count * 1_000_000_000.0 / wall,
                             rel_tol=1e-9), f"Invalid export throughput at {check}")
        require(row["heap_observed_max_bytes"]
                >= max(row["heap_before_gc_bytes"], row["heap_after_bytes"]),
                f"Invalid observed heap at {check}")
        jdbc = int(row["engine"] == "orm")
        require([int(row[f"sessions_{phase}"]) for phase in ("before", "during", "after")]
                == [0, 1, 0] and
                [int(row[f"jdbc_{phase}"]) for phase in ("before", "during", "after")]
                == [0, jdbc, 0] and int(row["transactions_completed"]) == jdbc,
                f"Resource budget exceeded at {check}")
        require(row["transactions_during"] == row["transactions_before"]
                and row["transactions_after"] - row["transactions_before"]
                == row["transactions_completed"], f"Invalid transaction counters at {check}")
        require(row["cache"] in ("warm", "reopened"), f"Unexpected cache state at {check}")
        group = warm if row["cache"] == "warm" else reopened
        key = (scenario, chunk, repeat)
        require(key not in group, f"Duplicate export at {check}")
        group[key] = row
    require(warm.keys() == {
        (scenario, chunk, repeat)
        for scenario in SCENARIOS
        for chunk in chunks
        for repeat in range(1, repetitions + 1)
    }, "Incomplete warmed export matrix")
    reopen_chunk = 100 if 100 in chunks else chunks[0]
    require(reopened.keys() == {("repository", reopen_chunk, 1), ("indexed-export", reopen_chunk, 1)},
            "Incomplete reopened export matrix")
    for scenario in SCENARIOS:
        counts = {int(row["rows"]) for key, row in warm.items() if key[0] == scenario}
        require(len(counts) == 1, f"Result counts vary for {scenario}")
    counts = {scenario: int(warm[(scenario, chunks[0], 1)]["rows"]) for scenario in SCENARIOS}
    require(counts["rare"] < counts["combined"] < min(counts["path"], counts["author"], counts["time"])
            and max(counts["path"], counts["author"], counts["time"]) < documents,
            "Fixture does not exercise distinct selectivities")

    observed = defaultdict(set)
    windows = defaultdict(list)
    for row in measurements(root / "offsets.csv", OFFSET_COLUMNS):
        key = coordinate(row)
        offset = int(row["offset"])
        count = int(row["rows"])
        require(row["cache"] == "warm" and key in warm and int(row["documents"]) == documents
                and row["engine"] == warm[key]["engine"], f"Unexpected offset coordinate {key}")
        require(offset not in observed[key], f"Duplicate offset {offset} for {key}")
        observed[key].add(offset)
        require(offset >= 0 and count == min(key[1], int(warm[key]["rows"]) - offset)
                and count >= 0 and all(int(row[field]) > 0 for field in LATENCIES)
                and int(row["scroll_prefix_ns"]) >= int(row["scroll_chunk_ns"]),
                f"Invalid offset window {offset} for {key}")
        windows[(key[0], key[1], offset)].append(row)
    require(observed.keys() == warm.keys(), "Missing offset coordinates")
    for key, offsets in observed.items():
        require(offsets == sample_offsets(int(warm[key]["rows"]), key[1]),
                f"Wrong sampled offsets for {key}")

    summary = []
    for (scenario, chunk, offset), trials in sorted(windows.items()):
        endpoints = {(r["rows"], r["first_object_id"], r["last_object_id"]) for r in trials}
        require(len(trials) == repetitions and len(endpoints) == 1,
                f"Results vary between trials for {scenario}/{chunk}/{offset}")
        count = int(trials[0]["rows"])
        summary.append({
            "engine": SCENARIOS[scenario],
            "scenario": scenario,
            "documents": documents,
            "chunk_size": chunk,
            "offset": offset,
            "rows": count,
            **{f"median_{name}": statistics.median(int(row[name]) for row in trials)
               for name in LATENCIES},
        })
    return summary


def report(root, documents, repetitions, chunks, summary):
    with (root / "comparisons.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=summary[0].keys())
        writer.writeheader()
        writer.writerows(summary)

    def latency(row, name):
        return row[f"median_{name}_ns"] / 1_000_000

    lines = [
        f"### {documents:,} indexed commits, {repetitions} warmed trial(s) per coordinate",
        "",
        "Each comparison uses medians at an observed offset. The prefix starts a fresh cursor; "
        "the continuation chunk uses a cursor that already reached the offset.",
        "",
        "| Full export path | Chunk size | First sampled continuation faster than a page "
        "| First sampled prefix faster than a page | Deepest offset | Page / chunk / prefix (ms) |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for scenario in ("repository", "indexed-export"):
        for chunk in chunks:
            subset = [row for row in summary if row["scenario"] == scenario and row["chunk_size"] == chunk]
            deepest = subset[-1]
            continuation = next((f"{row['offset']:,}" for row in subset if row["offset"] > 0
                                 and latency(row, "scroll_chunk") < latency(row, "offset_page")), "none")
            prefix = next((f"{row['offset']:,}" for row in subset
                           if latency(row, "scroll_prefix") < latency(row, "offset_page")), "none")
            deepest_times = " / ".join(f"{latency(deepest, key):.3f}"
                                       for key in ("offset_page", "scroll_chunk", "scroll_prefix"))
            lines.append(f"| {scenario} | {chunk} | {continuation} | {prefix} "
                         f"| {deepest['offset']:,} | {deepest_times} |")
    lines.extend(("", "Thresholds describe sampled windows. A faster continuation does not include "
                  "the work of reaching its offset, and timings may vary at later samples."))
    (root / "comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--documents", type=int, required=True)
    parser.add_argument("--repetitions", type=int, required=True)
    parser.add_argument("--chunks", type=int, nargs="+", default=[25, 100, 500])
    args = parser.parse_args()
    summary = validate(args.directory, args.documents, args.chunks, args.repetitions)
    report(args.directory, args.documents, args.repetitions, args.chunks, summary)


if __name__ == "__main__":
    main()
