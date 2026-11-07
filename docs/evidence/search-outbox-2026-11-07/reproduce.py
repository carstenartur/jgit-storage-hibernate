#!/usr/bin/env python3
"""Recompute the retained outbox medians and chart using Python's standard library."""

import csv
import hashlib
import html
import json
import math
import statistics
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MODES = ("remote-direct-write-sync", "remote-outbox-eventual")
COLORS = ("#526780", "#007c83")


def main():
    manifest = json.loads((ROOT / "sources.json").read_text())
    for source in manifest["files"]:
        path = ROOT / source["path"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
            raise ValueError(f"Changed retained input: {source['path']}")
    summary = []
    restarts = []
    overhead = []
    for instances in (1, 2, 4):
        with (ROOT / f"{instances}-jvms/outbox.csv").open(newline="") as source:
            rows = list(csv.DictReader(source))
        if len(rows) != 14 or any(int(row["instances"]) != instances for row in rows):
            raise ValueError(f"Incomplete topology: {instances}")
        if any(int(row["queued_after_visible"]) != 0 for row in rows):
            raise ValueError("An observed queue was not drained")
        for mode in MODES:
            selected = [row for row in rows if row["mode"] == mode]
            if len(selected) != 7:
                raise ValueError(f"Incomplete mode: {instances}/{mode}")
            restart, = [row for row in selected if row["phase"] == "restart"]
            backlog = 50 if mode == MODES[1] else 0
            if (int(restart["queued_before_restart"]) != backlog
                    or int(restart["indexed_documents"]) != 1650 + backlog):
                raise ValueError("Incorrect replay result")
            boot = float(restart["boot_to_visible_ms"])
            if not math.isfinite(boot) or boot <= 0:
                raise ValueError("Invalid restart duration")
            restarts.append((instances, mode, f"{boot:.3f}", backlog, 0, 1650 + backlog))
            for documents in (50, 500):
                burst = [row for row in selected
                         if row["phase"] == "burst" and int(row["documents"]) == documents]
                if len(burst) != 3:
                    raise ValueError("Incomplete burst repetitions")
                metrics = []
                for field in ("ack_ms", "ack_to_visible_ms"):
                    values = [float(row[field]) for row in burst]
                    if any(not math.isfinite(value) or value < 0 for value in values):
                        raise ValueError(f"Invalid duration: {field}")
                    metrics.append(statistics.median(values))
                summary.append((instances, mode, documents, 3, *metrics))
                traffic = []
                for field in ("postgres_network_bytes", "search_network_bytes"):
                    values = [int(row[field]) for row in burst]
                    if any(value <= 0 for value in values):
                        raise ValueError(f"Invalid container traffic: {field}")
                    traffic.append(statistics.median(values))
                overhead.append((instances, mode, documents, *traffic,
                                 max(int(row["outbox_relations_bytes"]) for row in burst)))
    with (ROOT / "medians.csv").open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("instances", "mode", "documents", "observations",
                         "median_ack_ms", "median_ack_to_visible_ms"))
        writer.writerows((*row[:4], *(f"{value:.3f}" for value in row[4:])) for row in summary)
    with (ROOT / "restart.csv").open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("instances", "mode", "boot_to_visible_ms", "queued_before_restart",
                         "queued_after_visible", "indexed_documents"))
        writer.writerows(restarts)
    with (ROOT / "overhead.csv").open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("instances", "mode", "documents", "median_postgres_network_bytes",
                         "median_search_network_bytes", "max_outbox_relations_bytes"))
        writer.writerows(overhead)
    render(summary)
    print("Verified 42 raw observations; wrote 12 burst medians, 6 restarts and latency.svg")


def render(rows):
    width, height = 1050, 414
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
           f'viewBox="0 0 {width} {height}" role="img" aria-label="Acknowledgement and visibility by JVM count">',
           '<rect width="1050" height="414" rx="12" fill="#f7f9fc"/>',
           '<g font-family="system-ui, sans-serif" fill="#24324b">']

    def text(x, y, value, size=12, anchor="start", weight=400):
        svg.append(f'<text x="{x}" y="{y}" font-size="{size}" text-anchor="{anchor}" '
                   f'font-weight="{weight}">{html.escape(str(value))}</text>')

    text(22, 29, "Shared PostgreSQL + OpenSearch: acknowledgement and visibility", 20, weight=700)
    text(22, 51, "Three bursts per point · 50 ms visibility polling · acknowledgement guarantees differ", 13)
    for panel, (title, metric) in enumerate((("Burst acknowledgement", 4),
                                            ("Acknowledgement to complete visibility", 5))):
        left, top, bottom, plot_width = 67 + panel * 515, 108, 306, 437
        maximum = max(row[metric] for row in rows)
        scale = 10 ** math.floor(math.log10(maximum))
        ceiling = math.ceil(maximum / scale) * scale
        text(left, 83, title, 15, weight=700)
        text(left + plot_width, 101, "ms", 11, "end")
        for tick in range(5):
            value = ceiling * tick / 4
            y = bottom - (bottom - top) * tick / 4
            svg.append(f'<line x1="{left}" x2="{left + plot_width}" y1="{y}" y2="{y}" stroke="#d6deec"/>')
            text(left - 7, y + 4, f"{value:.0f}", 10, "end")
        for position, (instances, documents) in enumerate((n, d) for n in (1, 2, 4) for d in (50, 500)):
            x = left + (position + .5) * plot_width / 6
            text(x, 325, f"{instances} JVM", 11, "middle")
            text(x, 341, f"{documents} docs", 10, "middle")
            for series, mode in enumerate(MODES):
                row, = [r for r in rows if r[0] == instances and r[1] == mode and r[2] == documents]
                value = row[metric]
                bar_height = value * (bottom - top) / ceiling
                svg.append(f'<rect x="{x + (series - 1) * 23:.1f}" y="{bottom - bar_height:.1f}" '
                           f'width="21" height="{bar_height:.1f}" rx="2" fill="{COLORS[series]}">'
                           f'<title>{mode}: {value:.3f} ms</title></rect>')
    for series, label in enumerate(("Direct write-sync", "Outbox eventual")):
        x = 67 + series * 215
        svg.append(f'<rect x="{x}" y="369" width="12" height="12" rx="2" fill="{COLORS[series]}"/>')
        text(x + 19, 380, label, 12)
    svg.extend(("</g>", "</svg>", ""))
    (ROOT / "latency.svg").write_text("\n".join(svg))


if __name__ == "__main__":
    main()
