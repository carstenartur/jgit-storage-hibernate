#!/usr/bin/env python3
"""Summarize raw ConcurrentPublicationBenchmark throughput runs and render evidence charts."""

import argparse
import csv
import json
import statistics
from pathlib import Path


POLICIES = ("direct", "size", "contention-aware")
POLICY_LABELS = ("Direct", "1 MiB size", "Contention-aware")
COLORS = ("#778395", "#3971A4", "#15816E")
LAYOUTS = ("shared", "independent")


def read_results(evidence):
    trials = {}
    with (evidence / "trials.csv").open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            trials[row["file"]] = row
    rows = []
    for raw in sorted((evidence / "raw").glob("*.json")):
        trial = trials[raw.name]
        results = json.loads(raw.read_text(encoding="utf-8"))
        if len(results) != 1 or results[0]["mode"] != "thrpt":
            raise ValueError(f"{raw}: expected one throughput result")
        result = results[0]
        parameters = result["params"]
        metrics = result["secondaryMetrics"]
        layout = "shared" if result["benchmark"].endswith("SameRepository") else "independent"
        payload_kib = int(parameters["payloadKiB"]) or (int(parameters["payloadMiB"]) * 1024 or 256)

        def score(name):
            return float(metrics[name]["score"])

        def ms(name):
            return score(name) / 1000

        publications = score("publications")
        pre_persisted = score("prePersistedPublicationSelections")
        if publications <= 0 or not 0 <= pre_persisted <= publications:
            raise ValueError(f"{raw}: expected at most one pre-persisted pack per operation")
        rows.append({
            "file": raw.name,
            "include_in_comparison": trial["include_in_comparison"],
            "deployment": parameters["deployment"],
            "backend": parameters["backend"],
            "writers": result["threads"],
            "payload_kib": payload_kib,
            "layout": layout,
            "policy": parameters["publicationPolicy"],
            "repeat": trial["repeat"],
            "throughput_ops_s": result["primaryMetric"]["score"],
            "publications": round(publications),
            "pre_persisted_packs": round(pre_persisted),
            "pre_persisted_percent": 100 * pre_persisted / publications,
            "pack_ref_p50_ms": ms("packAndRefPublicationDuration:p0.50"),
            "pack_ref_p95_ms": ms("packAndRefPublicationDuration:p0.95"),
            "pack_ref_p99_ms": ms("packAndRefPublicationDuration:p0.99"),
            "pack_lock_acquisition_p95_ms": ms("packPublicationLockAcquisition:p0.95"),
            "mean_pack_lock_held_ms": ms("packPublicationLockHeldMicros") / publications,
            "transactions_per_op": score("storageTransactions") / publications,
            "unlocked_pack_transactions_per_op": score("unlockedPackWriteTransactions") / publications,
            "mean_unlocked_pack_write_ms": ms("unlockedPackWriteDurationMicros") / publications,
            "observed_peak_heap_mib": score("publicationObservedHeapPeak") / (1024 * 1024),
            "allocated_mib_per_op": score("gc.alloc.rate.norm") / (1024 * 1024),
        })
    if not rows or len(rows) != len(trials):
        raise ValueError("raw JMH JSON and trial manifest must contain the same files")
    return rows


def charts(evidence, rows):
    import matplotlib.pyplot as plt

    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "axes.spines.top": False,
        "axes.spines.right": False,
        "svg.hashsalt": "pack-publication",
        "font.size": 9,
    })
    main = [
        row for row in rows
        if row["backend"] == "postgresql-hikari"
        and row["deployment"] == "native-routed"
        and row["writers"] == 8
        and row["payload_kib"] == 768
        and row["include_in_comparison"] == "yes"
    ]
    if not main:
        raise ValueError("no retained PostgreSQL/Hikari comparison rows")

    def values(layout, policy, field):
        return [row[field] for row in main if row["layout"] == layout and row["policy"] == policy]

    def bars(ax, layout, field, title, unit, aggregate=statistics.mean):
        groups = [values(layout, policy, field) for policy in POLICIES]
        ax.bar(range(3), [aggregate(group) for group in groups], color=COLORS, width=0.65)
        for index, group in enumerate(groups):
            jitter = [(offset - (len(group) - 1) / 2) * 0.052 for offset in range(len(group))]
            ax.scatter([index + offset for offset in jitter], group, color="#182130", s=15, zorder=3)
        ax.set_xticks(range(3), POLICY_LABELS, fontsize=8)
        ax.set_title(title)
        ax.set_ylabel(unit)
        ax.set_ylim(bottom=0)
        ax.grid(axis="y", alpha=0.23)
        ax.set_axisbelow(True)

    def save(fig, file_name, title):
        fig.suptitle(title, fontsize=13, fontweight="bold")
        fig.supxlabel(
            "PostgreSQL 17 / Hikari, 8 writers, 768 KiB, shaped loopback; dots are JMH forks",
            fontsize=8,
        )
        output = evidence / file_name
        fig.savefig(output, bbox_inches="tight", metadata={"Date": None})
        # Matplotlib leaves spaces at line ends inside SVG path attributes.
        lines = output.read_text(encoding="utf-8").splitlines()
        output.write_text("".join(line.rstrip() + "\n" for line in lines), encoding="utf-8")
        plt.close(fig)

    fig, axes = plt.subplots(1, 2, figsize=(8, 3.2), layout="constrained")
    for ax, layout in zip(axes, LAYOUTS, strict=True):
        bars(ax, layout, "throughput_ops_s", f"{layout.title()} repositories", "Operations/s")
    save(fig, "throughput.svg", "Pack and ref publication throughput")

    fig, axes = plt.subplots(1, 2, figsize=(8, 3.2), layout="constrained")
    for ax, layout in zip(axes, LAYOUTS, strict=True):
        bars(ax, layout, "pack_lock_acquisition_p95_ms", f"{layout.title()} repositories",
             "p95 lock acquisition (ms)", statistics.median)
    save(fig, "lock-acquisition.svg", "Final pack lock acquisition, including database RTT")

    fig, axes = plt.subplots(1, 2, figsize=(8, 3.2), layout="constrained")
    for ax, layout in zip(axes, LAYOUTS, strict=True):
        bars(ax, layout, "pre_persisted_percent", f"{layout.title()} repositories", "Pre-persisted packs (%)")
        ax.set_ylim(0, 106)
    save(fig, "selected-path.svg", "Share of chunked packs pre-persisted by policy")

    fig, axes = plt.subplots(1, 2, figsize=(8, 3.2), layout="constrained")
    bars(axes[0], "shared", "mean_pack_lock_held_ms", "Final pack transaction", "Lock held per op (ms)")
    bars(axes[1], "shared", "mean_unlocked_pack_write_ms", "Unlocked payload transaction",
         "Payload write per op (ms)")
    save(fig, "transaction-tradeoff.svg", "Shared repository: lock time and extra write phase")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--charts", action="store_true")
    args = parser.parse_args()
    rows = read_results(args.evidence)
    with (args.evidence / "per-fork.csv").open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=rows[0].keys(), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    if args.charts:
        charts(args.evidence, rows)


if __name__ == "__main__":
    main()
