#!/usr/bin/env python3
"""Verify the original diagnostic and reproduce descriptive GC observations offline."""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
SOURCE = "96f1b08d5d0ca32f98508828c78074f12da688fc"
TREE = "273b30da2c6b007b2e54133091ce403dc2a151d6"
RUN = 42533525581
ARTIFACT = 13638597050


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(name):
    path = PurePosixPath(name)
    require(name and "\\" not in name and not path.is_absolute() and ".." not in path.parts
            and str(path) == name.rstrip("/"), "Unsafe archive path")
    return path


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_csv(path, rows):
    with path.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def gc_events(raw, summary):
    rows = []
    for fork in summary["forks"]:
        events = {}
        for name in fork["logs"]:
            if "-gc.log" not in name:
                continue
            for line in (raw / name).read_text().splitlines():
                match = re.search(r"^\[(\d+)ms\].*GC\((\d+)\).* (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms$", line)
                if match:
                    uptime, identifier, before, after, capacity, duration = match.groups()
                    events[int(identifier)] = {
                        "fork": fork["ordinal"], "policy": fork["policy"],
                        "uptimeMillis": int(uptime), "gcId": int(identifier),
                        "usedBeforeMiB": int(before), "usedAfterMiB": int(after),
                        "heapCapacityMiB": int(capacity), "pauseMillis": float(duration),
                        "full": "Pause Full" in line, "gcLocker": "GCLocker Initiated GC" in line,
                    }
        require(len(events) == fork["gcCollections"], "GC event extraction disagrees with measured parser")
        rows.extend(sorted(events.values(), key=lambda row: row["uptimeMillis"]))
    return rows


def plot(events, output):
    import matplotlib
    matplotlib.use("Agg")
    matplotlib.rcParams.update({"svg.hashsalt": "publication-gc-2026-11-08", "svg.fonttype": "none"})
    import matplotlib.pyplot as plt
    figure, axes = plt.subplots(2, 2, figsize=(11, 6.4), sharey=True)
    for index, axis in enumerate(axes.flat, 1):
        rows = [row for row in events if row["fork"] == index]
        full = [row for row in rows if row["full"]]
        axis.plot([row["uptimeMillis"] / 1000 for row in rows],
                  [row["usedAfterMiB"] for row in rows], color="#2563eb", linewidth=1,
                  label="Used heap after each completed GC")
        axis.scatter([row["uptimeMillis"] / 1000 for row in full],
                     [row["usedAfterMiB"] for row in full], color="#dc2626", s=28,
                     zorder=3, label="After full compaction")
        axis.axhline(1024, color="#64748b", linestyle="--", linewidth=.8)
        axis.set_title(f"Fork {index}: {rows[0]['policy']}")
        axis.set_xlabel("JVM uptime, seconds")
        axis.set_ylabel("Heap used after GC, MiB")
        axis.set_ylim(0, 1080)
        axis.grid(alpha=.2)
    handles, labels = axes[0, 0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="lower center", ncol=2, frameon=False)
    figure.suptitle("Fixed four-fork diagnostic: full GC returned occupancy to 83–96 MiB")
    figure.tight_layout(rect=(0, .06, 1, .95))
    path = output / "post-gc-heap.svg"
    figure.savefig(path, metadata={"Date": None})
    path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()) + "\n")
    plt.close(figure)


def replay(output, plots=False):
    manifest = json.loads((ROOT / "provenance.json").read_text())
    require(manifest["sourceCommit"] == SOURCE and manifest["sourceTree"] == TREE,
            "Diagnostic source identity changed")
    run = manifest["run"]
    require(run["id"] == RUN and run["head_sha"] == SOURCE and run["run_attempt"] == 1
            and run["status"] == "completed" and run["conclusion"] == "success", "Original run differs")
    require(len(manifest["artifacts"]) == 1, "Unexpected diagnostic artifact set")
    artifact = manifest["artifacts"][0]
    archive = ROOT / manifest["archive"]["path"]
    digest = sha(archive.read_bytes())
    require(artifact["id"] == ARTIFACT and artifact["workflow_run"]["id"] == RUN
            and artifact["workflow_run"]["head_sha"] == SOURCE
            and digest == artifact["digest"].removeprefix("sha256:")
            and digest == manifest["archive"]["sha256"]
            and archive.stat().st_size == artifact["size_in_bytes"] == manifest["archive"]["bytes"],
            "Original diagnostic archive differs")
    source_archive = ROOT / "sources.tar.gz"
    require(sha(source_archive.read_bytes()) == manifest["sourcesArchiveSha256"], "Source archive differs")
    output = output.resolve()
    require(output != ROOT and not output.is_relative_to(ROOT / "archives"), "Unsafe replay destination")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="publication-gc-replay-") as temporary:
        temporary = Path(temporary)
        sources, raw = temporary / "sources", temporary / "raw"
        with tarfile.open(source_archive, "r:gz") as source_tar:
            members = source_tar.getmembers()
            require(len(members) == len(manifest["sourceFiles"])
                    and {m.name for m in members} == set(manifest["sourceFiles"]), "Source inventory differs")
            for member in members:
                require(member.isfile(), "Non-file source member")
                data = source_tar.extractfile(member).read()
                expected = manifest["sourceFiles"][member.name]
                require(sha(data) == expected["sha256"]
                        and hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest() == expected["gitBlob"],
                        "Measured source blob differs")
                path = sources.joinpath(*safe_path(member.name).parts)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
        with zipfile.ZipFile(archive) as retained:
            names = [item.filename for item in retained.infolist()]
            require(len(names) == len(set(names)), "Duplicate artifact member")
            require(sum(item.file_size for item in retained.infolist()) < 256 * 1024**2,
                    "Unexpected diagnostic expansion")
            for member in retained.infolist():
                require(not stat.S_ISLNK(member.external_attr >> 16), "Artifact symlink")
                path = raw.joinpath(*safe_path(member.filename).parts)
                if member.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(retained.read(member))
        scripts = sources / ".github/scripts"
        privacy = load_module("measured_privacy", scripts / "validate_search_database_telemetry.py")
        privacy.scan_privacy(raw)
        measured = load_module("measured_gc_diagnostic", scripts / "summarize-publication-gc-diagnostic.py")
        summary = measured.summarize(raw)
        encoded = (json.dumps(summary, indent=2) + "\n").encode()
        require(encoded == (raw / "summary.json").read_bytes(), "Hosted summary does not reproduce exactly")
        require(summary["sourceCommit"] == SOURCE and summary["sourceTree"] == TREE
                and summary["diagnosticOnly"] is True and summary["attemptedForks"] == 4,
                "Diagnostic identity or count changed")
        (output / "summary.json").write_bytes(encoded)
        events = gc_events(raw, summary)
        write_csv(output / "gc-events.csv", events)
        full = [row for row in events if row["full"]]
        write_csv(output / "full-gc-events.csv", full)
        if plots:
            plot(events, output)
        verification = {"sourceCommit": SOURCE, "sourceTree": TREE, "run": RUN,
                        "artifact": ARTIFACT, "archiveSha256": digest,
                        "hostedSummaryByteIdentical": True, "forks": 4,
                        "gcEvents": len(events), "fullGcEvents": len(full),
                        "acceptanceInference": False}
        (output / "verification.json").write_text(json.dumps(verification, indent=2) + "\n")
        print(json.dumps(verification))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plots", action="store_true")
    arguments = parser.parse_args()
    replay(arguments.output, arguments.plots)
