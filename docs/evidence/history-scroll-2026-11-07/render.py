"""Render the hosted history scroll measurements from the retained summary CSVs."""

import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter

ROOT = Path(__file__).resolve().parent
SIZES = [10_000, 100_000, 1_000_000]
SIZE_LABELS = ["10,000", "100,000", "1,000,000"]
BACKENDS = [("postgresql", "PostgreSQL"), ("sqlserver", "SQL Server")]
PATHS = [("repository", "ORM"), ("indexed-export", "Lucene")]
PALETTE = ("#0072B2", "#009E73", "#D55E00", "#8B5AA4")


def rows(name):
    with (ROOT / name).open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def select(data, backend, scenario, **fields):
    matches = [
        row for row in data
        if row["backend"] == backend and row["scenario"] == scenario and row["chunk_size"] == "100"
        and all(row[key] == str(value) for key, value in fields.items())
    ]
    matches.sort(key=lambda row: int(row["documents"]))
    assert [int(row["documents"]) for row in matches] == SIZES
    return matches


def save(fig, name):
    path = ROOT / f"{name}.svg"
    fig.savefig(path, metadata={"Date": None})
    plt.close(fig)
    text = path.read_text(encoding="utf-8")
    path.write_text("\n".join(line.rstrip() for line in text.splitlines()) + "\n", encoding="utf-8")


def plot_access():
    data = rows("offset-comparisons.csv")
    deep = [row for row in data if int(row["offset"]) == int(row["documents"]) - 100]
    series = (
        ("median_offset_page_ns", "One offset page", "o", PALETTE[0]),
        ("median_scroll_chunk_ns", "Continue an open cursor", "s", PALETTE[1]),
        ("median_scroll_prefix_ns", "Open a cursor and traverse to the page", "^", PALETTE[2]),
    )
    fig, axes = plt.subplots(2, 2, sharex=True, sharey=True, figsize=(10.8, 6.8), layout="constrained")
    for (backend, name), row_axes in zip(BACKENDS, axes, strict=True):
        for (scenario, path), ax in zip(PATHS, row_axes, strict=True):
            points = select(deep, backend, scenario)
            for field, label, marker, color in series:
                ax.plot(SIZES, [float(row[field]) / 1e6 for row in points],
                        label=label, marker=marker, color=color, lw=2)
            ax.set_title(f"{name} · {path}", loc="left")
            ax.set(xscale="log", yscale="log", xticks=SIZES, xticklabels=SIZE_LABELS)
            ax.xaxis.set_minor_locator(FixedLocator([]))
            ax.grid(axis="y", alpha=.24, which="major")
    for ax in axes[:, 0]:
        ax.set_ylabel("Median latency (ms, log scale)")
    for ax in axes[-1]:
        ax.set_xlabel("Indexed commits")
    axes[0, 0].yaxis.set_major_formatter(FuncFormatter(lambda y, _: f"{y:g}"))
    handles, labels = axes[0, 0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncols=3, frameon=False)
    fig.suptitle("Reaching the last 100-row window\nThree warm trials per cell · GitHub Actions 42413402130",
                 fontweight="bold")
    save(fig, "deep-offset")


def plot_heap():
    data = rows("export-summary.csv")
    fig, axes = plt.subplots(1, 2, sharey=True, figsize=(10.8, 4.8), layout="constrained")
    series = (
        ("median_heap_observed_max_bytes", "Median observed peak", "o", PALETTE[0]),
        ("max_observed_heap_bytes", "Highest observed peak", "^", PALETTE[3]),
        ("median_heap_after_gc_bytes", "Median after close and GC", "s", PALETTE[1]),
    )
    for (scenario, path), ax in zip(PATHS, axes, strict=True):
        for (backend, name), style in zip(BACKENDS, ("-", "--"), strict=True):
            points = select(data, backend, scenario, cache="warm")
            for field, label, marker, color in series:
                ax.plot(SIZES, [int(row[field]) / 2**20 for row in points],
                        label=f"{name} · {label}", marker=marker, color=color, ls=style, lw=1.8)
        ax.axhline(2048, color="#555555", ls=":", label="2 GiB JVM cap")
        ax.set_title(path, loc="left")
        ax.set(xscale="log", yscale="log", xticks=SIZES, xticklabels=SIZE_LABELS, ylim=(15, 3200))
        ax.xaxis.set_minor_locator(FixedLocator([]))
        ax.yaxis.set_major_locator(FixedLocator([20, 50, 100, 200, 500, 1000, 2048]))
        ax.yaxis.set_major_formatter(FuncFormatter(lambda y, _: f"{y:,.0f}"))
        ax.grid(axis="y", alpha=.24, which="major")
        ax.set_xlabel("Indexed commits")
    axes[0].set_ylabel("Whole JVM heap (MiB, log scale)")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncols=2, frameon=False, fontsize=8)
    fig.suptitle("Heap while exporting and after cursor close\n100-row chunks · Three warm trials per cell",
                 fontweight="bold")
    save(fig, "heap")


if __name__ == "__main__":
    plt.rcParams.update({
        "svg.fonttype": "none", "svg.hashsalt": "history-scroll-42413402130",
        "font.size": 10, "axes.spines.top": False, "axes.spines.right": False,
    })
    plot_access()
    plot_heap()
