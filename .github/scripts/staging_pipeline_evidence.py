#!/usr/bin/env python3
"""Select bounded native staging experiments and validate their raw evidence."""
import argparse
import csv
import itertools
import json
from pathlib import Path
import re
import statistics

MIB = 1024 * 1024
MODES = ("SYNCHRONOUS", "VIRTUAL", "PLATFORM")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def matrix(profile, repeats):
    require(profile in ("smoke", "coverage", "full"), "unknown matrix profile")
    require(repeats in (1, 3, 5, 9), "repeats must be 1, 3, 5 or 9")
    if profile == "full":
        coordinates = itertools.product((16, 128, 512), (16, 32), (1, 4, 16),
                                         ("runner", "tmpfs", "paced"), (0, 5, 20, 50))
    elif profile == "coverage":
        coordinates = [(16, 32, 1, "runner", 0), (128, 16, 1, "runner", 0),
                       (512, 16, 1, "runner", 0), (128, 32, 4, "runner", 5),
                       (128, 16, 16, "runner", 20), (128, 16, 1, "paced", 50),
                       (128, 32, 4, "tmpfs", 20), (512, 32, 4, "paced", 50)]
    else:
        coordinates = [(16, 16, 1, "runner", 0)]
    return {"include": [dict(id=f"p{p}-b{b}-w{w}-{s}-r{r}", payload=p, window=b,
                             writers=w, storage=s, rtt=r, rate=150 if s == "paced" else 0,
                             repeats=repeats, warmups=0 if profile == "smoke" else 1)
                        for p, b, w, s, r in coordinates]}


def read_csv(path):
    with path.open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source))


def validate_trials(rows, repeats):
    expected = set(itertools.product(range(1, repeats + 1), MODES))
    actual = [(int(row["sample"]), row["mode"]) for row in rows]
    require(len(actual) == len(expected) and set(actual) == expected, "trials are not complete")
    coordinates = set()
    for row in rows:
        values = {key: int(value) for key, value in row.items() if key != "mode"}
        require(all(value >= 0 for value in values.values()), "negative trial observation")
        p, b, w = (values[key] for key in ("payload_bytes", "window_mib", "writers"))
        require(p in [m * MIB + 257 for m in (16, 128, 512)], "unsupported payload")
        require(b in (16, 32) and w in (1, 4, 16), "unsupported window or writer count")
        coordinates.add((p, b, w, values["requested_rtt_ms"], values["rtt_nanos"], values["read_mib_per_second"]))
        chunks = (p + MIB - 1) // MIB
        windows = 1 if row["mode"] == "SYNCHRONOUS" or chunks <= b else 2
        require(values["buffer_bound_bytes"] == w * windows * min(b, chunks) * MIB,
                "buffer bound must match at most two configured windows per writer")
        require(values["staging_written_bytes"] == p and values["staging_read_bytes"] == p * w,
                "temporary byte accounting does not match the shared fixture")
        require(values["wall_nanos"] > 0, "elapsed transfer time must be positive")
        rate = values["read_mib_per_second"]
        if rate:
            # Permit 10% timer/pacing tolerance; cached reads alone cannot qualify this coordinate.
            require(values["staging_read_nanos"] * rate * MIB * 11 >= p * w * 1_000_000_000 * 10,
                    "paced trial did not observe the configured staging read limit")
        require(values["overlap_nanos"] <= min(values["staging_read_nanos"], values["jdbc_window_nanos"]),
                "overlap exceeds a measured phase")
        require(row["mode"] != "SYNCHRONOUS" or values["overlap_nanos"] == 0,
                "synchronous phases must not overlap")
    require(len(coordinates) == 1, "cell contains different coordinates")


def validate_rollbacks(rows, repeats, window):
    expected = set(itertools.product(range(1, repeats + 1), MODES, ("READER", "JDBC")))
    actual = [(int(row["sample"]), row["mode"], row["failure_stage"]) for row in rows]
    require(len(actual) == len(expected) and set(actual) == expected, "rollback observations are not complete")
    for row in rows:
        require(int(row["window_mib"]) == window and int(row["payload_bytes"]) > window * MIB,
                "rollback fixture must exceed its writer window")
        require(int(row["failure_to_return_nanos"]) >= int(row["rollback_nanos"]) > 0,
                "rollback time must be positive and within detection-to-return")
        require("first_window_flushed_nanos" in row and "failure_detected_nanos" in row,
                "rollback needs first-flush and failure-detection chronology")
        require(int(row["failure_detected_nanos"]) >= int(row["first_window_flushed_nanos"]) > 0,
                "rollback failure detection must follow the completed first flush")
        require(int(row["flushed_chunks"]) == window, "rollback must follow one flushed window")
        require(int(row["remaining_packs"]) == int(row["remaining_chunks"]) == 0,
                "rollback left durable pack/chunk rows")


def validate_timeline(rows, trials):
    by_trial = {}
    for row in rows:
        key = (row["sample"], row["mode"])
        require(row["phase"] in ("staging-read", "jdbc-window", "reader-wait"), "unknown timeline phase")
        require(all(int(row[field]) >= 0 for field in ("writer", "start_nanos", "duration_nanos", "bytes")),
                "negative timeline observation")
        by_trial.setdefault(key, []).append(row)
    require(set(by_trial) == {(row["sample"], row["mode"]) for row in trials}, "timeline trials are not complete")
    for trial in trials:
        spans = by_trial[(trial["sample"], trial["mode"])]
        require({int(row["writer"]) for row in spans} == set(range(int(trial["writers"]))),
                "timeline omits a writer")
        require(sum(int(row["bytes"]) for row in spans) == int(trial["staging_read_bytes"]),
                "timeline bytes disagree with trial")
        for phase, column in (("staging-read", "staging_read_nanos"), ("jdbc-window", "jdbc_window_nanos"),
                              ("reader-wait", "reader_wait_nanos")):
            require(sum(int(row["duration_nanos"]) for row in spans if row["phase"] == phase) == int(trial[column]),
                    "timeline duration disagrees with trial")
        overlap = 0
        for writer in range(int(trial["writers"])):
            reads = [row for row in spans if int(row["writer"]) == writer and row["phase"] == "staging-read"]
            writes = [row for row in spans if int(row["writer"]) == writer and row["phase"] == "jdbc-window"]
            for read, write in itertools.product(reads, writes):
                start = max(int(read["start_nanos"]), int(write["start_nanos"]))
                end = min(int(row["start_nanos"]) + int(row["duration_nanos"]) for row in (read, write))
                overlap += max(0, end - start)
        require(overlap == int(trial["overlap_nanos"]), "timeline overlap disagrees with trial")


def summarize(directory, expected_commit=None):
    environment = {}
    for line in (directory / "environment.properties").read_text().splitlines():
        if line and not line.startswith(("#", "!")):
            key, value = line.split("=", 1)
            environment[key] = value
    require(environment.get("measurement_boundary") == "staged-transfer-through-transaction-commit",
            "unrecognized measurement boundary")
    require(re.fullmatch(r"[0-9a-f]{40}", environment.get("source_commit", "")), "exact source commit is missing")
    require(expected_commit is None or expected_commit == environment["source_commit"], "source commit mismatch")
    require(environment.get("storage") in ("runner", "paced", "tmpfs"), "storage coordinate is missing")
    require(environment.get("storage") != "tmpfs" or environment.get("staging_filesystem") == "tmpfs",
            "tmpfs label lacks a measured tmpfs filesystem")
    require(environment.get("rollback_writers") == "1", "rollback scope must be explicit")
    repeats = int(environment["repeats"])
    require(repeats in (1, 3, 5, 9), "unsupported retained repetition count")
    trials = read_csv(directory / "trials.csv")
    validate_trials(trials, repeats)
    rollbacks = read_csv(directory / "rollback.csv")
    validate_rollbacks(rollbacks, repeats, int(trials[0]["window_mib"]))
    timeline = read_csv(directory / "timeline.csv")
    validate_timeline(timeline, trials)
    calibration = read_csv(directory / "rtt-calibration.csv")
    require(len(calibration) == 9, "nine RTT calibration samples required")
    require({int(row["sample"]) for row in calibration} == set(range(1, 10))
            and all(int(row["rtt_nanos"]) > 0 for row in calibration), "invalid RTT samples")
    rtt = statistics.median(int(row["rtt_nanos"]) for row in calibration)
    require(rtt == int(trials[0]["rtt_nanos"]), "calibration and trial RTT disagree")
    requested = int(trials[0]["requested_rtt_ms"])
    require(requested in (0, 5, 20, 50), "unsupported requested RTT")
    require(rtt < 5_000_000 if requested == 0 else requested * 900_000 <= rtt <= (requested + 8) * 1_000_000,
            "observed RTT does not qualify the requested coordinate")
    require(environment["storage"] != "paced" or int(trials[0]["read_mib_per_second"]) > 0,
            "paced storage requires an injected read rate")
    observations = json.loads((directory / "database-telemetry.json").read_text())["observations"]
    require(len(observations) == repeats * 3, "native observations are not complete")
    require({(item["coordinate"]["sample"], item["coordinate"]["mode"]) for item in observations}
            == {(row["sample"], row["mode"]) for row in trials}, "native coordinates disagree")
    for item in observations:
        require(item["backend"] == "postgresql" and item["enabled"] is True,
                "native PostgreSQL observation required")
        require(item["counters"].get("postgresql.wal.insert_lsn_bytes", 0) > 0, "native WAL delta required")
        expected_coordinate = dict(payloadMiB=str(int(trials[0]["payload_bytes"]) // MIB),
                                   windowMiB=trials[0]["window_mib"], writers=trials[0]["writers"],
                                   rttNanos=trials[0]["rtt_nanos"])
        require(all(item["coordinate"].get(key) == value for key, value in expected_coordinate.items()),
                "native coordinate disagrees with the measured trial")
    modes = {}
    for mode in MODES:
        selected = [row for row in trials if row["mode"] == mode]
        stats = {}
        for column in ("wall_nanos", "cpu_nanos", "allocated_bytes", "heap_peak_upper_bytes", "buffer_bound_bytes",
                       "staging_read_nanos", "jdbc_window_nanos", "reader_wait_nanos", "overlap_nanos"):
            values = [int(row[column]) for row in selected]
            stats[column] = dict(median=statistics.median(values), minimum=min(values), maximum=max(values))
        for stage in ("READER", "JDBC"):
            selected_failures = [row for row in rollbacks if row["mode"] == mode and row["failure_stage"] == stage]
            stats[f"{stage.lower()}_failure_to_return_nanos"] = statistics.median(int(row["failure_to_return_nanos"]) for row in selected_failures)
            stats[f"{stage.lower()}_rollback_nanos"] = statistics.median(int(row["rollback_nanos"]) for row in selected_failures)
        modes[mode] = stats
    summary = dict(schema_version=1, environment=environment, qualified_transfer_samples=len(trials),
                   verified_rollback_samples=len(rollbacks), full_git_publication_measured=False,
                   production_decision="keep synchronous", coordinate={key: trials[0][key] for key in
                   ("payload_bytes", "window_mib", "writers", "requested_rtt_ms", "rtt_nanos", "read_mib_per_second")}, modes=modes)
    (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    write_charts(directory, summary, timeline)
    return summary


def write_charts(directory, summary, timeline):
    colors = dict(SYNCHRONOUS="#46657f", VIRTUAL="#007b73", PLATFORM="#9b5d23")
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="800" viewBox="0 0 1100 800">',
             '<rect width="1100" height="800" fill="#fff"/><g font-family="sans-serif" fill="#172936">',
             '<text x="30" y="30" font-size="20">Staged transfer through commit; full Git publication excluded</text>']
    for index, (column, title, divisor) in enumerate((
            ("wall_nanos", "Median transfer latency (ms)", 1_000_000),
            ("heap_peak_upper_bytes", "Sum of per-pool heap peaks (MiB; upper estimate)", MIB),
            ("cpu_nanos", "Process CPU (ms)", 1_000_000))):
        y = 65 + index * 155
        parts.append(f'<text x="30" y="{y}" font-size="16">{title}</text>')
        maximum = max(summary["modes"][mode][column]["median"] for mode in MODES) or 1
        for n, mode in enumerate(MODES):
            value = summary["modes"][mode][column]["median"]
            width = 740 * value / maximum
            parts += [f'<text x="30" y="{y + 30 + n * 34}" font-size="13">{mode}</text>',
                      f'<rect x="150" y="{y + 15 + n * 34}" width="{width:.1f}" height="22" fill="{colors[mode]}"/>',
                      f'<text x="{160 + width:.1f}" y="{y + 30 + n * 34}" font-size="13">{value / divisor:.2f}</text>']
    chosen = [row for row in timeline if row["sample"] == "1" and row["writer"] == "0" and row["phase"] != "reader-wait"]
    maximum = max(int(row["start_nanos"]) + int(row["duration_nanos"]) for row in chosen) or 1
    parts.append('<text x="30" y="550" font-size="16">Sample 1, writer 0: staging reads (blue) and JDBC windows (orange)</text>')
    for n, mode in enumerate(MODES):
        parts.append(f'<text x="30" y="{585 + n * 60}" font-size="13">{mode}</text>')
        for row in chosen:
            if row["mode"] == mode:
                x = 150 + 900 * int(row["start_nanos"]) / maximum
                width = max(.5, 900 * int(row["duration_nanos"]) / maximum)
                reading = row["phase"] == "staging-read"
                y = 570 + n * 60 + (0 if reading else 22)
                color = "#3978ae" if reading else "#c57722"
                parts.append(f'<rect x="{x:.1f}" y="{y}" width="{width:.1f}" height="18" fill="{color}"/>')
    parts.append(f'<text x="150" y="770" font-size="13">Timeline endpoint: {maximum / 1_000_000:.2f} ms. Production decision: keep synchronous.</text></g></svg>')
    (directory / "pipeline.svg").write_text("\n".join(parts) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    select = sub.add_parser("matrix")
    select.add_argument("--profile", choices=("smoke", "coverage", "full"), default="smoke")
    select.add_argument("--repeats", type=int, default=3)
    verify = sub.add_parser("summarize")
    verify.add_argument("directory", type=Path)
    verify.add_argument("--expected-commit")
    args = parser.parse_args()
    if args.command == "matrix":
        print(json.dumps(matrix(args.profile, args.repeats), separators=(",", ":")))
    else:
        result = summarize(args.directory, args.expected_commit)
        print(json.dumps({key: result[key] for key in ("qualified_transfer_samples", "verified_rollback_samples", "production_decision")}, indent=2))


if __name__ == "__main__":
    main()
