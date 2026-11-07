"""Validate retained reflog sizing observations and regenerate their table and charts."""

import argparse
import csv
import gzip
import json
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path

NANOS_PER_MS = 1_000_000
AXES = ("stripes", "pool", "producers", "scope", "rtt", "repeat", "warmup", "measured", "message")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def open_raw(root, name):
    plain = root / name
    return (
        plain.open(encoding="utf-8", newline="")
        if plain.is_file()
        else gzip.open(root / (name + ".gz"), "rt", encoding="utf-8", newline="")
    )


def group_csv(root, name):
    groups = defaultdict(list)
    with open_raw(root, name) as stream:
        for row in csv.DictReader(stream):
            key = row.pop("coordinate")
            groups[key].append({k: int(v) for k, v in row.items()})
    return groups


def percentile(rows, p, column):
    ordered = sorted(rows, key=lambda row: row[column])
    return ordered[math.ceil(len(ordered) * p / 100) - 1]


def milliseconds(nanos):
    return nanos / NANOS_PER_MS


def run_id(c):
    return (
        f"{c['scope']}-s{c['stripes']}-p{c['pool']}-w{c['producers']}"
        f"-rtt{c['rtt']}-m{c['message']}-r{c['repeat']}"
        + ("-" + c["workload"] if "workload" in c else "")
    )


def validate(root):
    with (root / "plan.csv").open(encoding="utf-8", newline="") as stream:
        plan = {}
        for values in csv.reader(line for line in stream if not line.startswith("#")):
            if not values:
                continue
            run = {
                name: value if name == "scope" else int(value)
                for name, value in zip(AXES, values[:len(AXES)], strict=True)
            }
            if len(values) != len(AXES):
                require(len(values) == len(AXES) + 1 and values[-1] in ("control", "pack"),
                        "Mixed trials must specify control or pack")
                run["workload"] = values[-1]
            key = run_id(run)
            require(key not in plan, f"Duplicate plan row: {key}")
            plan[key] = run

    acks = group_csv(root, "acknowledgements.csv")
    borrows = group_csv(root, "pool-borrows.csv")
    occupancy = group_csv(root, "occupancy.csv")
    calibration = group_csv(root, "rtt-calibration.csv")
    with open_raw(root, "native.ndjson") as stream:
        observations = [json.loads(line) for line in stream]
    native = {row["coordinate"]["id"]: row for row in observations}

    require(len(native) == len(observations), "Native observations contain duplicate runs")
    for group in (native, acks, borrows, occupancy, calibration):
        require(group.keys() == plan.keys(), "Evidence does not cover the exact requested plan")
    require(
        len({(item["coordinate"]["revision"], item["serverVersion"]) for item in observations})
        == 1,
        "Mixed source revisions or PostgreSQL versions",
    )

    mixed = any("workload" in run for run in plan.values())
    if mixed:
        require(all("workload" in run for run in plan.values()),
                "Keep the mixed and reflog-only sizing profiles in separate plans")
        timeline = group_csv(root, "mixed-ack-timeline.csv")
        publications = group_csv(root, "pack-publications.csv")
        pack_borrows = group_csv(root, "pack-borrows.csv")
        require(timeline.keys() == plan.keys(), "Missing or unexpected acknowledgement timeline")
        pack_runs = {key for key, run in plan.items() if run["workload"] == "pack"}
        require(publications.keys() == pack_borrows.keys() == pack_runs,
                "Pack evidence must cover exactly the pack trials")

    for key, run in plan.items():
        n = native[key]
        c = n["coordinate"]
        for source, dest in (
            ("stripes", "stripes"),
            ("poolSize", "pool"),
            ("producers", "producers"),
            ("requestedRttMillis", "rtt"),
            ("repeat", "repeat"),
            ("warmupCommandsPerProducer", "warmup"),
            ("measuredCommandsPerProducer", "measured"),
            ("messageChars", "message"),
        ):
            require(int(c[source]) == run[dest], f"{key}: {source} does not match plan")
        require(c["repositoryScope"] == run["scope"], f"{key}: unexpected layout")
        if mixed:
            require(c["workload"].lower() == run["workload"], f"{key}: unexpected workload")
        require(
            n["backend"] == "postgresql"
            and n["serverVersion"].split()[0] == "17.10"
            and int(c["elapsedNanos"]) > 0
            and int(c["rejectedSubmissions"]) == 0
            and n["counters"]["postgresql.wal.insert_lsn_bytes"] > 0,
            f"{key}: database, elapsed time, rejection or WAL evidence invalid",
        )
        a = acks[key]
        p = borrows[key]
        pool_by_id = {r["batch_id"]: r for r in p}
        require(
            len(a) == run["producers"] * run["measured"]
            and {(r["producer"], r["ordinal"]) for r in a}
            == {(w, i) for w in range(run["producers"]) for i in range(run["measured"])},
            f"{key}: missing, duplicate or unexpected acknowledgements",
        )
        active = 1 if run["scope"] == "shared" else min(run["stripes"], run["producers"])
        require(
            len(p) == len(pool_by_id) == int(c["committedBatches"])
            and len({r["worker_thread_id"] for r in p}) == int(c["activeStripes"]) == active
            and all(r["hold_nanos"] > 0 and r["acquisition_nanos"] >= 0 for r in p),
            f"{key}: invalid transaction timings, count or active stripes",
        )
        counts = Counter(r["batch_id"] for r in a)
        p95_acknowledgement = percentile(a, 95, "ack_nanos")
        tail = None
        by_batch = defaultdict(set)
        for r in a:
            require(
                0 <= r["queue_wait_nanos"] <= r["ack_nanos"] and 1 <= r["batch_size"] <= 50,
                f"{key}: invalid queue time or batch size",
            )
            if run["scope"] == "independent":
                require(r["batch_size"] == 1, f"{key}: combined independent repositories")
            batch = r["batch_id"]
            if batch == 0:
                continue
            require(batch in pool_by_id, f"{key}: acknowledgement has no matching borrow")
            acquisition = pool_by_id[batch]["acquisition_nanos"]
            hold = pool_by_id[batch]["hold_nanos"]
            remainder = r["ack_nanos"] - r["queue_wait_nanos"] - acquisition - hold
            require(remainder >= 0, f"{key}: latency phases overlap")
            if r is p95_acknowledgement:
                tail = {**r, "acquisition_nanos": acquisition, "hold_nanos": hold, "other_nanos": remainder}
            by_batch[batch].add(r["batch_size"])
        require(
            all(len(sizes) == 1 and counts[batch] <= next(iter(sizes))
                for batch, sizes in by_batch.items()),
            f"{key}: mismatched batch membership",
        )
        require(counts[0] <= max(1, len(a) // 100), f"{key}: too few paired acknowledgements")
        require(tail is not None, f"{key}: p95 acknowledgement has no paired transaction")
        if counts[0] == 0:
            require(
                by_batch.keys() == pool_by_id.keys()
                and all(counts[batch] == next(iter(sizes)) for batch, sizes in by_batch.items()),
                f"{key}: recorded batch sizes do not add up",
            )

        cal = calibration[key]
        median_rtt = milliseconds(statistics.median(r["select_1_nanos"] for r in cal))
        require(
            len(cal) == 20 and {r["ordinal"] for r in cal} == set(range(1, 21))
            and (median_rtt < 5 if run["rtt"] == 0 else run["rtt"] * 0.9 <= median_rtt <= run["rtt"] + 8),
            f"{key}: bad SELECT 1 calibration",
        )
        samples = occupancy[key]
        require(
            samples and all(r["pool_total"] == run["pool"] for r in samples)
            and all(0 <= r["pool_active"] <= run["pool"] and r["heap_used_bytes"] > 0 for r in samples),
            f"{key}: invalid sampled pool occupancy",
        )

        row = {
            "id": key,
            **run,
            "actual_rtt_ms": median_rtt,
            "ack_count": len(a),
            "paired_count": len(a) - counts[0],
            "elapsed_ms": milliseconds(int(c["elapsedNanos"])),
            "records_per_second": len(a) * 1_000_000_000 / int(c["elapsedNanos"]),
            "active_stripes": active,
            "transactions": len(p),
            "records_per_transaction": len(a) / len(p),
            "rejections": int(c["rejectedSubmissions"]),
        }
        for label, entries, column in (
            ("ack", a, "ack_nanos"),
            ("queue", a, "queue_wait_nanos"),
            ("pool_acquisition", p, "acquisition_nanos"),
            ("transaction_hold", p, "hold_nanos"),
        ):
            for pct in (50, 95, 99):
                row[f"{label}_p{pct}_ms"] = milliseconds(percentile(entries, pct, column)[column])
        for column in ("ack_nanos", "queue_wait_nanos", "acquisition_nanos", "hold_nanos", "other_nanos"):
            row["paired_tail_" + column.replace("_nanos", "_ms")] = milliseconds(tail[column])
        for label in ("pool_total", "pool_active", "pool_waiters", "heap_used_bytes",
                      "queue_depth", "queued_bytes", "db_lock_waiters"):
            row[label + "_sample_peak"] = max(r[label] for r in samples)
        row["wal_insert_bytes"] = n["counters"]["postgresql.wal.insert_lsn_bytes"]
        row["wal_syncs_reported"] = n["counters"].get("postgresql.wal.syncs", "")
        row["wal_sync_ms_reported"] = (
            n["counters"]["postgresql.wal.sync_time_micros"] / 1000
            if "postgresql.wal.sync_time_micros" in n["counters"] else ""
        )
        sizes = Counter(r["batch_size"] for r in a)
        require(all(count % size == 0 for size, count in sizes.items()),
                f"{key}: acknowledgements do not form whole batches")
        histogram = Counter({size: count // size for size, count in sizes.items()})
        require(sum(histogram.values()) == len(p), f"{key}: wrong measured batch count")
        if mixed:
            row.update(mixed_metrics(key, run, a, acks, borrows, timeline[key],
                                     publications, pack_borrows, int(c["elapsedNanos"])))
        yield row, histogram


def mixed_metrics(key, run, acknowledgements, all_acknowledgements, reflog_borrows,
                  timeline, publications, pack_borrows, reflog_elapsed):
    def coordinate(row):
        return row["producer"], row["ordinal"]

    submissions = {coordinate(row): row for row in timeline}
    require(len(submissions) == len(timeline) == len(acknowledgements),
            f"{key}: missing or duplicate timed submissions")
    for ack in acknowledgements:
        sample = submissions[coordinate(ack)]
        started = sample["submitted_offset_nanos"]
        completed = sample["completed_offset_nanos"]
        require(0 <= started < completed <= reflog_elapsed and completed - started == ack["ack_nanos"],
                f"{key}: inconsistent submission interval")
        if ack["ordinal"] > 0:
            previous = submissions[(ack["producer"], ack["ordinal"] - 1)]
            require(previous["completed_offset_nanos"] <= started,
                    f"{key}: producer issued a command before its previous acknowledgement")

    result = {
        "pack_flush_ms": "",
        "pack_hold_sum_ms": "",
        "pack_checkout_count": "",
        "pack_checkout_max_ms": "",
        "pack_payload_bytes": "",
        "pack_committed_chunks": "",
        "pack_cold_read_bytes": "",
        "overlapping_ack_count": "",
        "overlapping_ack_p95_ms": "",
        "matched_control_ack_p95_ms": "",
        "overlapping_ack_checkout_paired_count": "",
        "overlapping_ack_checkout_p95_ms": "",
        "matched_control_ack_checkout_paired_count": "",
        "matched_control_ack_checkout_p95_ms": "",
    }
    if run["workload"] == "control":
        return result

    require(len(publications[key]) == 1, f"{key}: expected exactly one pack publication")
    pack = publications[key][0]
    started = pack["started_offset_nanos"]
    completed = pack["completed_offset_nanos"]
    holds = pack_borrows[key]
    require(0 <= started < completed and pack["payload_bytes"] == (8 << 20) + 257
            and pack["committed_chunks"] >= 8 and pack["reopen_read_bytes"] >= pack["payload_bytes"],
            f"{key}: invalid chunked publication or cold verification")
    require(holds and all(
        started <= hold["acquired_offset_nanos"] < hold["acquired_offset_nanos"] + hold["hold_nanos"]
        <= completed and hold["acquisition_nanos"] >= 0 for hold in holds
    ), f"{key}: invalid publisher checkout")
    overlap = {
        coordinate(ack) for ack in timeline if any(
            ack["submitted_offset_nanos"] < hold["acquired_offset_nanos"] + hold["hold_nanos"]
            and ack["completed_offset_nanos"] > hold["acquired_offset_nanos"] for hold in holds
        )
    }
    require(len(overlap) == pack["overlapping_acknowledgements"] > 0,
            f"{key}: missing or inconsistent overlap")
    control = run_id({**run, "workload": "control"})
    require(control in all_acknowledgements, f"{key}: missing matched reflog-only control")
    exposed = [ack for ack in acknowledgements if coordinate(ack) in overlap]
    matched = [ack for ack in all_acknowledgements[control] if coordinate(ack) in overlap]
    require(len(exposed) == len(matched) == len(overlap), f"{key}: unmatched control submissions")

    def paired_checkout_summary(trial, samples):
        by_batch = {row["batch_id"]: row for row in reflog_borrows[trial]}
        observations = [by_batch[ack["batch_id"]] for ack in samples if ack["batch_id"] in by_batch]
        if not observations:
            return 0, ""
        return len(observations), milliseconds(
            percentile(observations, 95, "acquisition_nanos")["acquisition_nanos"]
        )

    exposed_checkout_count, exposed_checkout_p95 = paired_checkout_summary(key, exposed)
    control_checkout_count, control_checkout_p95 = paired_checkout_summary(control, matched)
    result.update(
        pack_flush_ms=milliseconds(completed - started),
        pack_hold_sum_ms=milliseconds(sum(hold["hold_nanos"] for hold in holds)),
        pack_checkout_count=len(holds),
        pack_checkout_max_ms=milliseconds(max(hold["acquisition_nanos"] for hold in holds)),
        pack_payload_bytes=pack["payload_bytes"],
        pack_committed_chunks=pack["committed_chunks"],
        pack_cold_read_bytes=pack["reopen_read_bytes"],
        overlapping_ack_count=len(overlap),
        overlapping_ack_p95_ms=milliseconds(percentile(exposed, 95, "ack_nanos")["ack_nanos"]),
        matched_control_ack_p95_ms=milliseconds(percentile(matched, 95, "ack_nanos")["ack_nanos"]),
        overlapping_ack_checkout_paired_count=exposed_checkout_count,
        overlapping_ack_checkout_p95_ms=exposed_checkout_p95,
        matched_control_ack_checkout_paired_count=control_checkout_count,
        matched_control_ack_checkout_p95_ms=control_checkout_p95,
    )
    return result


def render(rows, histograms, output):
    import matplotlib

    matplotlib.use("Agg")
    matplotlib.rcParams.update({
        "svg.fonttype": "none", "svg.hashsalt": "reflog-pool-sizing",
        "font.size": 10, "axes.spines.top": False, "axes.spines.right": False,
    })
    import matplotlib.pyplot as plt

    def save(fig, name):
        path = output / f"{name}.svg"
        fig.savefig(path, bbox_inches="tight", metadata={"Date": None})
        plt.close(fig)
        svg = path.read_text(encoding="utf-8")
        path.write_text("\n".join(line.rstrip() for line in svg.splitlines()) + "\n", encoding="utf-8")

    def select(stripes, pool, producers, scope, rtt):
        return [
            r for r in rows
            if (r["stripes"], r["pool"], r["producers"], r["scope"], r["rtt"], r["message"])
            == (stripes, pool, producers, scope, rtt, 13)
        ]

    def series(runs):
        return [r["records_per_second"] for r in runs]

    stripes = [1, 2, 4, 8]
    pools = [2, 4, 8, 16]
    matrix = [[statistics.median(series(select(s, p, 16, "independent", 0))) for p in pools]
              for s in stripes]
    fig, ax = plt.subplots(figsize=(6.5, 4.7), layout="constrained")
    scale = ax.imshow(matrix, cmap="Blues", aspect="auto", vmin=0)
    ax.set(xticks=range(4), xticklabels=pools, yticks=range(4), yticklabels=stripes,
           xlabel="Hikari pool slots", ylabel="Writer stripes",
           title="Local PostgreSQL: 16 producers, independent repositories")
    for i, row in enumerate(matrix):
        for j, value in enumerate(row):
            ax.text(j, i, f"{value:,.0f}", ha="center", va="center",
                    color="white" if value > scale.norm.vmax * .64 else "#172840")
    fig.colorbar(scale, ax=ax, label="Acknowledged records/s · median across trials")
    save(fig, "throughput")

    fig, plots = plt.subplots(2, 2, figsize=(10, 6.5), layout="constrained")
    cases = [(1, 4, "shared", "S1 P4"), (4, 4, "shared", "S4 P4"),
             (1, 4, "independent", "I1 P4"), (4, 4, "independent", "I4 P4"),
             (8, 2, "independent", "I8 P2"), (8, 8, "independent", "I8 P8")]
    phases = [("queue_wait", "Queue", "#0072B2"), ("acquisition", "Pool acquisition", "#E69F00"),
              ("hold", "Connection hold", "#009E73"), ("other", "Other", "#999999")]
    for ax, rtt in zip(plots.flat, [0, 5, 20, 50], strict=True):
        runs = []
        for s, p, scope, _ in cases:
            candidates = sorted(select(s, p, 16, scope, rtt), key=lambda r: r["ack_p95_ms"])
            runs.append(candidates[len(candidates) // 2])
        bottom = [0.0] * len(cases)
        for phase, label, color in phases:
            values = [r[f"paired_tail_{phase}_ms"] for r in runs]
            ax.bar(range(len(cases)), values, bottom=bottom, label=label, color=color)
            bottom = [x + y for x, y in zip(bottom, values, strict=True)]
        ax.set(xticks=range(len(cases)), xticklabels=[case[-1] for case in cases],
               title="Direct loopback" if rtt == 0 else f"{rtt} ms requested RTT")
    for ax in plots[:, 0]:
        ax.set_ylabel("Acknowledgement time (ms)")
    handles, labels = plots[0, 0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncols=4, fontsize=9)
    fig.suptitle("The command at p95 in the middle trial · 16 producers\n"
                 "S = shared, I = independent; numbers are stripes and pool slots")
    save(fig, "latency")

    fig, ax = plt.subplots(figsize=(8, 4.4), layout="constrained")
    cases = [
        ([(4, "independent"), (1, "shared")], "Independent · 4 / shared · 1 producer", "#0072B2"),
        ([(4, "shared")], "Shared · 4 producers", "#009E73"),
        ([(16, "shared")], "Shared · 16 producers", "#CC79A7"),
    ]
    for layouts, label, color in cases:
        hist = Counter()
        for workers, scope in layouts:
            for run in select(4, 4, workers, scope, 0):
                hist.update(histograms[run["id"]])
        total = sum(hist.values())
        require(total > 0, f"No measured batches for {label}")
        ax.plot(range(1, 17), [100 * hist[i] / total for i in range(1, 17)],
                "o-", label=label, color=color, markersize=3)
    ax.set(xticks=range(1, 17), xlabel="Records in one committed batch",
           ylabel="Share of measured transactions (%)",
           title="Local batch sizes · 4 stripes, 4 pool slots")
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncols=2, fontsize=9)
    save(fig, "batch-sizes")

    fig, plots = plt.subplots(1, 2, figsize=(9.4, 4.5), layout="constrained")
    for ax, rtt, xs in zip(plots, [0, 20], [stripes, [1, 4]], strict=True):
        for scope, color in (("shared", "#0072B2"), ("independent", "#D55E00")):
            y = [series(select(s, 4, 16, scope, rtt)) for s in xs]
            middle = [statistics.median(v) for v in y]
            low = [m - min(v) for m, v in zip(middle, y, strict=True)]
            high = [max(v) - m for m, v in zip(middle, y, strict=True)]
            ax.errorbar(xs, middle, yerr=[low, high], color=color, label=scope, marker="o", capsize=4)
        ax.set(xticks=xs, xlabel="Writer stripes", ylabel="Acknowledged records/s",
               title="Direct loopback" if rtt == 0 else "20 ms requested RTT", ylim=(0, None))
        ax.legend()
    fig.suptitle("Repository scaling · 4 pool slots, 16 producers\n"
                 "Median and observed range across trials")
    save(fig, "repository-scaling")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--charts", action="store_true", help="generate SVG charts (needs Matplotlib)")
    args = parser.parse_args()
    output = args.output or args.raw
    output.mkdir(parents=True, exist_ok=True)
    rows, counters = zip(*validate(args.raw), strict=True)
    with (output / "per-trial.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, rows[0].keys(), lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: f"{v:.6f}" if isinstance(v, float) else v for k, v in row.items()})
    if args.charts:
        require("workload" not in rows[0], "--charts requires the standard reflog-only sizing plan")
        render(rows, {r["id"]: c for r, c in zip(rows, counters, strict=True)}, output)
    print(f"Validated {len(rows)} runs, {sum(row['ack_count'] for row in rows):,} acknowledgements, "
          f"{sum(row['transactions'] for row in rows):,} transactions.")


if __name__ == "__main__":
    main()
