#!/usr/bin/env python3
"""Render comparable local shard observations from SearchRoutingPerformanceIT."""

import argparse
import csv
import html
import math
from collections import defaultdict
from pathlib import Path


COLORS = ("#007c83", "#9b381f")
LAYOUT_NAMES = {"single": "One repository", "uniform": "Uniform", "skewed": "80% in one repository"}


def label(x, y, value, *, size=13, anchor="start", weight=400, color="#24324b"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" text-anchor="{anchor}" font-size="{size}" '
            f'font-weight="{weight}" fill="{color}">{html.escape(str(value))}</text>')


def render(groups, title, subtitle, series, unit, include_baseline=False):
    width = max(520, 346 * len(groups) + 40)
    height = 316
    plot_width = (width - 40) / len(groups)
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
           f'viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}">',
           f'<rect width="{width}" height="{height}" rx="12" fill="#f7f9fc"/>',
           '<g font-family="system-ui, sans-serif">', label(22, 30, title, size=20, weight=700),
           label(22, 52, subtitle, size=13)]
    for col, (layout, rows) in enumerate(groups.items()):
        left = 22 + col * plot_width
        top, bottom = 110, 253
        gx = left + 54
        gw = plot_width - 69
        svg.append(label(left, 90, LAYOUT_NAMES[layout], size=14, weight=700))
        chosen = [row for row in rows if include_baseline or row['mode'] == 'routed']
        if not chosen:
            continue
        values = [float(row[key]) for row in chosen for _, key in series if row[key]]
        ceiling = max(values)
        quantum = 10 ** math.floor(math.log10(ceiling))
        ceiling = math.ceil(ceiling / quantum) * quantum
        svg.append(label(gx + gw, top - 7, unit, size=11, anchor="end"))
        for tick in range(4):
            value = ceiling * tick / 3
            y = bottom - (bottom - top) * tick / 3
            svg.append(f'<line x1="{gx:.1f}" x2="{gx + gw:.1f}" y1="{y:.1f}" y2="{y:.1f}" stroke="#d6deec"/>')
            svg.append(label(gx - 6, y + 4, f'{value:,.0f}', size=10, anchor="end"))
        spacing = gw / len(chosen)
        bar = min(28, spacing / (len(series) + 1))
        for position, row in enumerate(chosen):
            x = gx + (position + .5) * spacing
            axis = "Base" if row['mode'] == 'baseline' else row['shards']
            svg.append(label(x, bottom + 17, axis, size=12, anchor="middle"))
            for i, (_, key) in enumerate(series):
                if not row[key]:
                    continue
                value = float(row[key])
                dy = (bottom - top) * value / ceiling
                bx = x + (i - len(series) / 2) * bar
                color = "#5b6c87" if row['mode'] == 'baseline' else COLORS[i]
                svg.append(f'<rect x="{bx:.1f}" y="{bottom - dy:.1f}" width="{bar - 2:.1f}" '
                           f'height="{dy:.1f}" rx="2" fill="{color}"><title>{value:,.1f} {unit}</title></rect>')
        svg.append(label(gx + gw / 2, bottom + 36, "Lucene shards", anchor="middle", size=11))
    for i, (name, _) in enumerate(series):
        x = 22 + 175 * i
        svg.append(f'<rect x="{x}" y="297" width="11" height="11" rx="2" fill="{COLORS[i]}"/>')
        svg.append(label(x + 17, 307, name, size=12))
    if include_baseline:
        svg.append('<rect x="197" y="297" width="11" height="11" rx="2" fill="#5b6c87"/>')
        svg.append(label(214, 307, "Unrouted baseline", size=12))
    return "\n".join([*svg, "</g>", "</svg>", ""])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    groups = defaultdict(list)
    with args.csv.open(encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            groups[row['layout']].append(row)
    if not groups:
        raise ValueError("No shard observations")
    endpoints = {('baseline', 1), ('routed', 1), ('routed', 8)}
    full = endpoints | {('routed', 2), ('routed', 4)}
    for rows in groups.values():
        coordinates = {(row['mode'], int(row['shards'])) for row in rows}
        if len(coordinates) != len(rows) or coordinates not in (endpoints, full):
            raise ValueError(f"Unexpected shard comparison: {coordinates}")
        rows.sort(key=lambda row: (row['mode'] != 'baseline', int(row['shards'])))
    documents, = {int(row['initial_documents']) for rows in groups.values() for row in rows}
    args.output.mkdir(parents=True, exist_ok=True)
    latency = render(groups, "Repository text query latency (p95)",
                     f"H2 + local Lucene · {documents:,} initial documents · same filter and sort",
                     (("Routed", "text_routed_p95_us"), ("Fan-out", "text_fanout_p95_us")), "µs")
    throughput = render(groups, "Initial projection indexing throughput",
                        f"H2 + local Lucene · {documents:,} initial documents · sync commits",
                        (("Repository routing", "indexed_docs_per_second"),), "docs/s", True)
    (args.output / "query-latency.svg").write_text(latency, encoding="utf-8")
    (args.output / "indexing-throughput.svg").write_text(throughput, encoding="utf-8")


if __name__ == "__main__":
    main()
