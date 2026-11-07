"""Validate one history scroll benchmark cell and summarize its observed offsets."""

import argparse
import csv
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


def require(condition, message):
    if not condition:
        raise ValueError(message)


def rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


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
    for row in rows(root / "exports.csv"):
        scenario, chunk, repeat = coordinate(row)
        count = int(row["rows"])
        check = f"{scenario}/{chunk}/{repeat}/{row['cache']}"
        require(int(row["documents"]) == documents and SCENARIOS.get(scenario) == row["engine"],
                f"Wrong export fixture at {check}")
        require(0 <= count <= documents and (count == 0) == (scenario == "empty"),
                f"Unexpected result count at {check}")
        if scenario in ("repository", "indexed-export"):
            require(count == documents, f"Missing full export results at {check}")
        require(int(row["chunks"]) == (count + chunk - 1) // chunk
                and int(row["max_chunk_rows"]) == min(chunk, count),
                f"Chunk budget exceeded at {check}")
        quantiles = [int(row[f"chunk_p{p}_upper_ns"]) for p in (50, 95, 99)]
        require(quantiles == sorted(quantiles) and (quantiles[0] > 0) == (count > 0),
                f"Invalid chunk latencies at {check}")
        jdbc = int(row["engine"] == "orm")
        require([int(row[f"sessions_{phase}"]) for phase in ("before", "during", "after")]
                == [0, 1, 0] and
                [int(row[f"jdbc_{phase}"]) for phase in ("before", "during", "after")]
                == [0, jdbc, 0] and int(row["transactions_completed"]) == jdbc,
                f"Resource budget exceeded at {check}")
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
    for row in rows(root / "offsets.csv"):
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
