#!/usr/bin/env python3
"""Replay the original paired study, preserving incomplete cells as incomplete.

No Java, network or database access. Python 3.9+ validates and derives tables;
Matplotlib is optional for the confidence-bound figure.
"""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import stat
import sys
import tarfile
import tempfile
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
SOURCE = "5ed8aad0f0691bcecb414b319235b67dc0520581"
TREE = "8a6035b7563d2901bf8d34f62f2c879bc1cb0601"
RUN = 42431132779
CELLS = ("independent-1w-0ms", "independent-1w-50ms",
         "independent-16w-0ms", "independent-16w-50ms", "shared-8w-5ms")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def safe_name(name):
    path = PurePosixPath(name)
    require(name and "\\" not in name and not path.is_absolute()
            and ".." not in path.parts and str(path) == name.rstrip("/"),
            "Unsafe archive member")
    return path


def extract(path, expected, destination):
    require(sha(path.read_bytes()) == expected, "Original archive digest differs: " + path.name)
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        names = [str(safe_name(item.filename)) for item in members]
        require(len(names) == len(set(names)), "Duplicate archive member")
        require(sum(item.file_size for item in members) < 128 * 1024**2,
                "Unexpected archive expansion")
        for item in members:
            require(not stat.S_ISLNK(item.external_attr >> 16), "Archive symlink")
            target = destination.joinpath(*safe_name(item.filename).parts)
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(item))


def sources(manifest, destination):
    path = ROOT / "sources.tar.gz"
    require(sha(path.read_bytes()) == manifest["sourcesArchiveSha256"], "Source archive differs")
    expected = manifest["sourceFiles"]
    with tarfile.open(path, "r:gz") as archive:
        members = archive.getmembers()
        require(len(members) == len(expected) and {m.name for m in members} == set(expected),
                "Source inventory differs")
        for item in members:
            require(item.isfile(), "Non-file source member")
            target = destination.joinpath(*safe_name(item.name).parts)
            data = archive.extractfile(item).read()
            record = expected[item.name]
            blob = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            require(sha(data) == record["sha256"] and blob == record["gitBlob"],
                    "Measured source blob differs: " + item.name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    return (module("measured_paired", destination / ".github/scripts/summarize-publication-nonregression.py"),
            module("measured_privacy", destination / ".github/scripts/validate_search_database_telemetry.py"))


def csv_bytes(rows):
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue().encode()


def figure(rows, output):
    import matplotlib
    matplotlib.use("Agg")
    matplotlib.rcParams.update({"svg.hashsalt": "publication-paired-2026-11-08",
                                "svg.fonttype": "none", "font.size": 11})
    import matplotlib.pyplot as plt

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), gridspec_kw={"width_ratios": [1.5, 1]})
    left, right = axes
    independent = [row for row in rows if row["cell"].startswith("independent")]
    for index, row in enumerate(independent):
        if row["outcome"] == "incomplete":
            left.text(.02, index, "Aborted: no acceptance bound", color="#64748b",
                      transform=left.get_yaxis_transform(), va="center")
        else:
            low, center = row["lowerBoundPercent"], row["throughputChangePercent"]
            left.plot([low, center], [index, index], color="#0369a1", lw=2)
            left.plot(low, index, "|", color="#0369a1", ms=13)
            left.plot(center, index, "o", color="#0369a1")
    left.set_yticks(range(len(independent)), [row["cell"].replace("independent-", "") for row in independent])
    left.invert_yaxis()
    left.axvline(-5, color="#b91c1c", ls="--", label="−5% margin")
    left.axvline(0, color="#94a3b8", lw=1)
    left.set_title("Independent repositories\nPoint estimate + corrected one-sided lower bound")
    lows = [row["lowerBoundPercent"] for row in independent if row["outcome"] != "incomplete"]
    centers = [row["throughputChangePercent"] for row in independent if row["outcome"] != "incomplete"]
    left.set_xlim(min([-6] + lows) - .3, max([1.5] + centers) + .3)
    left.legend(loc="upper left", frameon=False)

    shared = next(row for row in rows if row["cell"].startswith("shared"))
    if shared["outcome"] != "incomplete":
        center, low, high = (shared[key] for key in ("throughputChangePercent", "lowerBoundPercent", "upperBoundPercent"))
        right.errorbar(center, 0, xerr=[[center-low], [high-center]], fmt="o", capsize=6, color="#15803d")
        right.annotate(f"{center:+.2f}%\n95%: {low:+.2f}% to {high:+.2f}%", (center, 0),
                       xytext=(0, 24), textcoords="offset points", ha="center")
        right.set_xlim(min(low, 0) - 3, high + 3)
    else:
        right.text(.05, .5, "Incomplete: no inference", transform=right.transAxes)
    right.axvline(0, color="#94a3b8", lw=1)
    right.set_yticks([0], ["8 writers / 5 ms"])
    right.set_ylim(-.8, .8)
    right.set_title("Shared repository\nFixed 24 pairs, two-sided 95% interval")
    for ax in axes:
        ax.grid(axis="x", alpha=.2)
        ax.set_xlabel("Throughput change: contention-aware versus size, %")
    fig.suptitle("Preregistered PostgreSQL publication study — original outcomes retained", fontsize=14)
    fig.text(.5, .025, "Incomplete cells are not assigned zero performance; early-stopped point estimates can be upward-selected.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=[0, .065, 1, .91])
    path = output / "paired-bounds.svg"
    fig.savefig(path, metadata={"Date": None})
    path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()) + "\n")
    plt.close(fig)


def replay(output, plots=False):
    manifest = json.loads((ROOT / "provenance.json").read_text())
    require(manifest["sourceCommit"] == SOURCE and manifest["sourceTree"] == TREE,
            "Measured source changed")
    run = manifest["run"]
    require(run["id"] == RUN and run["head_sha"] == SOURCE and run["run_attempt"] == 1
            and run["event"] == "push" and run["status"] == "completed",
            "Original run is incomplete or relabelled")
    artifacts = manifest["artifacts"]
    require(len(artifacts) == 5 and {a["cell"] for a in artifacts} == set(CELLS)
            and len({a["id"] for a in artifacts}) == 5, "All five original cells must be retained")
    output = output.resolve()
    require(output != ROOT and not output.is_relative_to(ROOT / "archives"), "Unsafe output directory")
    output.mkdir(parents=True, exist_ok=True)
    rows, verified = [], []
    with tempfile.TemporaryDirectory(prefix="publication-paired-replay-") as temporary:
        temporary = Path(temporary)
        measured, privacy = sources(manifest, temporary / "sources")
        for cell in CELLS:
            artifact = next(a for a in artifacts if a["cell"] == cell)
            require(artifact["name"] == "publication-nonregression-" + cell
                    and artifact["workflow_run"]["id"] == RUN
                    and artifact["workflow_run"]["head_sha"] == SOURCE,
                    "Artifact came from another source/run")
            archive = ROOT / "archives" / (artifact["name"] + ".zip")
            require(archive.stat().st_size == artifact["size_in_bytes"], "Original archive length differs")
            raw = temporary / cell
            extract(archive, artifact["digest"].removeprefix("sha256:"), raw)
            privacy.scan_privacy(raw)
            protocol = measured.load_json(raw / "protocol.json")
            measured.validate_protocol(protocol)
            require(protocol["sourceCommit"] == SOURCE and protocol["sourceTree"] == TREE
                    and protocol["cell"] == cell, "Raw protocol was relabelled")
            job = next(j for j in manifest["jobs"] if j["id"] == artifact["jobId"])
            require(job["status"] == "completed" and job["run_id"] == RUN
                    and job["name"] == "Publication policy / " + cell, "Original job incomplete or relabelled")
            if job["conclusion"] == "success":
                result = measured.summarize(raw)
                require((raw / "summary.json").read_text() == json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n",
                        "Original summary does not replay")
                for field, name in (("pairs", "pairs.csv"), ("secondaryObservations", "secondary.csv")):
                    require((raw / name).read_bytes() == csv_bytes(result[field]), "Original derived CSV differs")
                destination = output / cell
                destination.mkdir(exist_ok=True)
                for name in ("summary.json", "pairs.csv", "secondary.csv"):
                    data = (raw / name).read_bytes()
                    # Original CRLF CSV bytes remain verified above and unchanged in the ZIP.
                    # Readable Git copies use LF consistently with the repository's text files.
                    (destination / name).write_bytes(data.replace(b"\r\n", b"\n") if name.endswith(".csv") else data)
                row = {key: result[key] for key in ("cell", "pairCount", "forkCount", "throughputChangePercent",
                    "lowerBoundPercent", "upperBoundPercent", "nonRegressionEstablished", "sharedImprovementEstablished", "stoppedEarly")}
                row["outcome"] = "complete"
            else:
                require(job["conclusion"] in ("failure", "timed_out", "cancelled"), "Unexpected job outcome")
                try:
                    measured.summarize(raw)
                except ValueError as failure:
                    rejection = str(failure)
                else:
                    raise ValueError("A failed original job was incorrectly accepted as complete")
                trials = measured.load_json(raw / "trials.json")
                row = dict(cell=cell, pairCount=len(trials)//2, forkCount=len(trials),
                           throughputChangePercent=None, lowerBoundPercent=None, upperBoundPercent=None,
                           nonRegressionEstablished=False, sharedImprovementEstablished=False,
                           stoppedEarly=False, outcome="incomplete")
                (output / (cell + "-incomplete.json")).write_text(json.dumps({
                    **row, "validatorRejection": rejection,
                    "qualification": "Original partial data retained; no acceptance interval calculated"}, indent=2) + "\n")
            rows.append(row)
            verified.append(dict(cell=cell, artifactId=artifact["id"], sha256=artifact["digest"], outcome=row["outcome"]))
    (output / "outcomes.csv").write_bytes(csv_bytes(rows).replace(b"\r\n", b"\n"))
    (output / "verification.json").write_text(json.dumps({
        "sourceCommit": SOURCE, "sourceTree": TREE, "runId": RUN,
        "archives": verified, "sourceFilesVerified": len(manifest["sourceFiles"]),
        "allIndependentNonRegressionEstablished": all(r["nonRegressionEstablished"] for r in rows if r["cell"].startswith("independent")),
        "qualification": "Original failed cells remain incomplete, regardless of later diagnostic outcomes",
    }, indent=2) + "\n")
    if plots:
        figure(rows, output)
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plots", action="store_true")
    args = parser.parse_args()
    results = replay(args.output, args.plots)
    print(json.dumps({"cells": len(results), "complete": sum(r["outcome"] == "complete" for r in results),
                      "incomplete": sum(r["outcome"] == "incomplete" for r in results)}))
