#!/usr/bin/env python3
"""Verify retained archives and replay eight original diagnostic passes offline.

Python's standard library is sufficient for validation and tables. Pass --plots
to additionally render SVG figures with Matplotlib. No Java or database is run.
"""

import argparse
import csv
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
SOURCE = "5ed8aad0f0691bcecb414b319235b67dc0520581"
TREE = "8a6035b7563d2901bf8d34f62f2c879bc1cb0601"
RUN = 42431132661
COORDINATES = [(scenario, resources) for scenario in ("reference", "sync-async-r500")
               for resources in ("normal", "constrained")]
EXPECTED_NAMES = {f"search-runtime-sustained-{scenario}-{resources}"
                  for scenario, resources in COORDINATES}
REQUIRED_STEPS = {
    "Verify diagnostic parser and lifecycle",
    "Run isolated PostgreSQL sustained and activity passes",
    "Validate exact-source completion and export observations",
    "Check diagnostic evidence for connection details",
}
EXPECTED_TESTS = {
    "SearchRuntimeDiagnosticWorkersTest": 3,
    "SearchRuntimeSustainedDiagnosticTest": 3,
    "SearchRuntimeSustainedDiagnosticIT": 1,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_provenance(data):
    run = data["run"]
    require(run["id"] == RUN and run["head_sha"] == SOURCE and run["head_tree"] == TREE
            and run["status"] == "completed" and run["conclusion"] == "success"
            and run["event"] == "push" and run["run_attempt"] == 1,
            "Wrong or incomplete original source run")
    artifacts = data["artifacts"]
    require(len(artifacts) == 4 and {item["name"] for item in artifacts} == EXPECTED_NAMES
            and len({item["id"] for item in artifacts}) == 4,
            "Expected all four unique artifact coordinates")
    for item in artifacts:
        require(item["workflow_run"]["id"] == RUN
                and item["workflow_run"]["head_sha"] == SOURCE
                and item["retained_archive"] == f"archives/{item['name']}.zip"
                and re.fullmatch(r"sha256:[0-9a-f]{64}", item["digest"])
                and item["size_in_bytes"] > 0,
                "Artifact source, path or digest is inconsistent")
    jobs = [job for job in data["jobs"] if job["name"].startswith("Sustained ")]
    expected_jobs = {f"Sustained {scenario} ({resources})" for scenario, resources in COORDINATES}
    require(len(jobs) == 4 and {job["name"] for job in jobs} == expected_jobs,
            "Expected all four successful job coordinates")
    for job in jobs:
        require(job["run_id"] == RUN and job["status"] == "completed"
                and job["conclusion"] == "success", "Incomplete sustained job")
        steps = {step["name"]: step for step in job["steps"]}
        require(REQUIRED_STEPS <= steps.keys()
                and all(steps[name]["conclusion"] == "success" for name in REQUIRED_STEPS)
                and any(name.startswith("Run actions/upload-artifact@")
                        and step["conclusion"] == "success" for name, step in steps.items()),
                "A native, source, privacy or upload gate did not pass")


def safe_path(name):
    path = PurePosixPath(name)
    require(name and "\\" not in name and not path.is_absolute()
            and ".." not in path.parts and str(path) == name.rstrip("/"),
            f"Unsafe archive path: {name!r}")
    return path


def extract_verified_archive(path, expected_digest, output):
    require(digest(path.read_bytes()) == expected_digest, f"Archive digest mismatch: {path.name}")
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        names = [str(safe_path(item.filename)) for item in members]
        require(len(set(names)) == len(names), "Repeated archive path")
        require(sum(item.file_size for item in members) < 32 * 1024**2, "Unexpected archive expansion")
        require(all(not stat.S_ISLNK(item.external_attr >> 16) for item in members),
                "Archive path is a symbolic link")
        output.mkdir(parents=True, exist_ok=False)
        for item in members:
            target = output.joinpath(*safe_path(item.filename).parts)
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(item))


def verify_sources(provenance, output):
    archive_path = ROOT / "sources.tar.gz"
    require(digest(archive_path.read_bytes()) == provenance["retained_sources_sha256"],
            "Source archive digest differs")
    expected = provenance["source_files"]
    require(len(expected) == 16, "Incomplete source snapshot")
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        require(len(members) == len(expected) and {member.name for member in members} == expected.keys(),
                "Source snapshot file set differs")
        for member in members:
            require(member.isfile(), "Source snapshot has a non-file entry")
            relative = safe_path(member.name)
            data = archive.extractfile(member).read()
            details = expected[member.name]
            blob = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            require(len(data) == details["bytes"] and digest(data) == details["sha256"]
                    and blob == details["git_blob"], f"Source blob differs: {member.name}")
            target = output.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    measured = ROOT / "tools/measured_summarizer.py"
    require(digest(measured.read_bytes()) == provenance["retained_summarizer_sha256"]
            and measured.read_bytes() == (output / ".github/scripts/summarize-search-runtime-diagnostic.py").read_bytes(),
            "Retained measured summarizer differs from original source")
    return load_module("original_diagnostic", measured), load_module(
        "original_privacy", output / ".github/scripts/validate_search_database_telemetry.py")


def validate_junit(extracted):
    reports = extracted / "jgit-storage-hibernate-benchmarks/target/surefire-reports"
    found = {}
    for report in sorted(reports.glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        name = suite.attrib["name"].rsplit(".", 1)[-1]
        require(name in EXPECTED_TESTS and name not in found, "Unexpected or repeated JUnit suite")
        require(int(suite.attrib["tests"]) == EXPECTED_TESTS[name]
                and all(int(suite.attrib[key]) == 0 for key in ("failures", "errors", "skipped"))
                and len(suite.findall("testcase")) == EXPECTED_TESTS[name]
                and not list(suite.iter("failure")) and not list(suite.iter("error"))
                and not list(suite.iter("skipped")), f"JUnit gate failed: {name}")
        found[name] = EXPECTED_TESTS[name]
    require(found == EXPECTED_TESTS, "Missing diagnostic JUnit suite")
    return sum(found.values())


def write_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def derive(summaries, inputs, output):
    observations, latencies, activity = [], [], []
    for summary in summaries:
        coordinate = {key: summary[key] for key in ("scenario", "resources", "pass")}
        raw = inputs[(summary["scenario"], summary["resources"], summary["pass"])]
        samples = raw["samples"]
        sustained_queries = summary["stable_query_latency_by_start_phase"]["sustained"]
        observations.append({
            **coordinate, "verified_commits": summary["verified_commits"],
            "transactions": summary["transaction_count"], "queries": summary["query_count"],
            "sustained_commits_per_second": summary["sustained_commits_per_second"],
            "sustained_query_observations": sustained_queries["observations"],
            "sustained_query_p95_ms": sustained_queries["p95_ms"],
            "gate_held_ms": summary["gate_held_ms"],
            "sustained_drain_ms": summary["sustained_drain_ms"],
            "overload_drain_ms": summary["overload_drain_ms"],
            "queue_backpressure_observed": summary["queue_backpressure_observed"],
            "max_ack_minus_observed_visible": summary["max_acknowledged_minus_last_observed_visible"],
            "max_visibility_observation_age_ms": summary["max_visibility_observation_age_ms"],
            "sampled_heap_max_mib": summary["sampled_heap_max_bytes"] / 1024**2,
            "heap_limit_mib": summary["heap_limit_bytes"] / 1024**2,
            "last_sample_cumulative_jvm_gc_count": int(samples[-1]["gc_count"]),
            "last_sample_cumulative_jvm_gc_time_ms": int(samples[-1]["gc_time_ms"]),
            "sample_count": len(samples),
        })
        for operation, phases in (("publication", summary["publication_latency"]),
                                  ("stable_query_by_start_phase", summary["stable_query_latency_by_start_phase"])):
            for phase, values in phases.items():
                latencies.append({**coordinate, "operation": operation, "phase": phase, **values})
        for phase, counts in summary["activity_by_phase"].items():
            activity.append({**coordinate, "event_phase": phase, **counts})
    write_csv(output / "observations.csv", observations)
    write_csv(output / "latencies.csv", latencies)
    write_csv(output / "activity.csv", activity)
    (output / "observations.json").write_text(json.dumps(summaries, indent=2, sort_keys=True, allow_nan=False) + "\n")


def plot(inputs, output):
    import matplotlib
    matplotlib.use("Agg")
    matplotlib.rcParams.update({"svg.hashsalt": "search-runtime-sustained-2026-11-08",
                                "font.size": 10, "axes.titlesize": 11, "svg.fonttype": "none"})
    import matplotlib.pyplot as plt
    for kind in ("publication-visibility", "query-latency", "heap-used"):
        figure, axes = plt.subplots(2, 2, figsize=(12, 8))
        figure.subplots_adjust(left=.075, right=.985, bottom=.15, top=.90,
                               wspace=.16, hspace=.28)
        for ax, (scenario, resources) in zip(axes.flat, COORDINATES):
            data = inputs[(scenario, resources, "timing")]
            start = data["phases"]["sustained"]
            end = data["phases"]["verify"]
            gate_start = int(data["done"]["gate.entered.elapsed.ns"])
            gate_end = int(data["done"]["gate.released.elapsed.ns"])
            seconds = lambda ns: (int(ns) - start) / 1e9
            samples = [row for row in data["samples"] if start <= int(row["elapsed_ns"]) <= end]
            x = [seconds(row["elapsed_ns"]) for row in samples]
            ax.axvspan(seconds(gate_start), seconds(gate_end), color="#cbd5e1", alpha=.65,
                       label="Injected I/O stall")
            ax.axvline(seconds(data["phases"]["overload"]), color="#64748b", ls=":", lw=1,
                       label="Overload begins")
            if kind == "publication-visibility":
                for key, label, color, style in (
                        ("submitted_commits", "Submitted", "#3b82f6", "-"),
                        ("acknowledged_commits", "Acknowledged", "#ca8a04", "--"),
                        ("last_visible_commits", "Last observed visible", "#15803d", "-")):
                    ax.step(x, [int(row[key]) for row in samples], where="post", label=label,
                            color=color, ls=style, lw=1.6)
                ax.set_ylim(950, 2550)
            elif kind == "query-latency":
                queries = [row for row in data["queries"] if start <= int(row["started_ns"]) <= end]
                ax.scatter([seconds(row["started_ns"]) for row in queries],
                           [(int(row["completed_ns"]) - int(row["started_ns"])) / 1e6 for row in queries],
                           s=10, color="#0369a1", alpha=.75, label="Stable query")
                ax.set_yscale("log")
                ax.set_ylim(.05, 3000)
            else:
                ax.plot(x, [int(row["heap_used_bytes"]) / 1024**2 for row in samples], color="#7e22ce",
                        lw=1.3, label="Heap used sample")
                limit = int(data["meta"]["heap.max.bytes"]) / 1024**2
                ax.axhline(limit, color="#b91c1c", ls="--", lw=1, label="MXBean heap maximum")
                ax.set_ylim(0, 1100)
            ax.set_title(f"{scenario} / {resources}")
            ax.set_xlim(0, seconds(end))
            ax.grid(alpha=.2)
        labels = {
            "publication-visibility": ("Publication and sampled visibility — untraced timing passes", "Commit count"),
            "query-latency": ("Stable-query observations — untraced timing passes", "Query latency (ms, log scale)"),
            "heap-used": ("Heap usage samples — includes objects awaiting collection", "Heap used (MiB)"),
        }
        title, ylabel = labels[kind]
        figure.suptitle(title, fontsize=15, y=.98)
        figure.supxlabel("Seconds from sustained phase start", y=.07)
        figure.supylabel(ylabel, x=.012)
        handles, legend_labels = axes.flat[0].get_legend_handles_labels()
        figure.legend(handles, legend_labels, loc="lower center", bbox_to_anchor=(.5, .008),
                      ncol=len(handles), frameon=False)
        path = output / f"{kind}.svg"
        figure.savefig(path, metadata={"Date": None})
        path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()) + "\n")
        plt.close(figure)


def replay(output, plots=False):
    provenance = json.loads((ROOT / "provenance.json").read_text())
    validate_provenance(provenance)
    output = output.resolve()
    require(output != ROOT and not output.is_relative_to(ROOT / "archives")
            and not output.is_relative_to(ROOT / "tools"), "Output must be separate from retained evidence")
    output.mkdir(parents=True, exist_ok=True)
    summaries, inputs, memberships, archived_hashes = [], {}, [], {}
    tests, comparisons = 0, 0
    with tempfile.TemporaryDirectory(prefix="search-sustained-replay-") as temporary:
        temporary = Path(temporary)
        measured, privacy = verify_sources(provenance, temporary / "sources")
        artifacts = {item["name"]: item for item in provenance["artifacts"]}
        for scenario, resources in COORDINATES:
            name = f"search-runtime-sustained-{scenario}-{resources}"
            artifact = artifacts[name]
            path = ROOT / artifact["retained_archive"]
            require(path.stat().st_size == artifact["size_in_bytes"], f"Archive length differs: {name}")
            expected_digest = artifact["digest"].removeprefix("sha256:")
            extracted = temporary / name
            extract_verified_archive(path, expected_digest, extracted)
            privacy.scan_privacy(extracted)
            tests += validate_junit(extracted)
            archived_hashes[name] = expected_digest
            base = extracted / "target/search-runtime-sustained"
            require((base / "cpu.json").is_file(), "Missing host CPU inventory")
            for pass_name in ("timing", "trace"):
                raw = base / "raw" / pass_name
                summary, events = measured.validate_evidence(raw)
                require((summary["scenario"], summary["resources"], summary["pass"])
                        == (scenario, resources, pass_name), "Pass differs from its artifact coordinate")
                require(summary["source_commit"] == SOURCE and summary["source_tree"] == TREE,
                        "Pass source differs from the published source")
                require(summary["verified_commits"] == 2500 and summary["transaction_count"] == 150,
                        "Hosted fixture differs from the declared bounded study")
                meta = measured.read_properties(raw / "metadata.properties")
                expected_settings = {"seed.commits": "1000", "batch.commits": "10",
                                     "sustained.batches": "100", "overload.batches": "50",
                                     "period.millis": "100", "gate.requested.hold.millis": "1500",
                                     "backend.threads": "1", "queue.count": "1", "queue.size": "8",
                                     "writer.ram.buffer.mib": "16", "writer.max.buffered.docs": "20"}
                require(all(meta.get(key) == value for key, value in expected_settings.items()),
                        "Hosted bounded workload/settings differ")
                generated = temporary / "replayed" / name / pass_name
                measured.write_summary(generated, summary, events)
                for filename in ("summary.json", "summary.md", "activity-events.csv"):
                    require((generated / filename).read_bytes() == (base / "derived" / pass_name / filename).read_bytes(),
                            f"Hosted derived bytes do not replay: {name}/{pass_name}/{filename}")
                    comparisons += 1
                memberships.append(tuple(row["object_id"] for row in measured.read_table(raw, "membership.csv")))
                summaries.append(summary)
                inputs[(scenario, resources, pass_name)] = {
                    "meta": meta, "done": measured.read_properties(raw / "completion.properties"),
                    "samples": measured.read_table(raw, "samples.csv"),
                    "queries": measured.read_table(raw, "queries.csv"),
                    "phases": {row["phase"]: int(row["elapsed_ns"]) for row in measured.read_table(raw, "trace.csv")
                               if row["component"] == "DIAGNOSTIC"},
                }
        require(len(set(memberships)) == 1, "Git identity fixture differs between passes")
        derive(summaries, inputs, output)
        if plots:
            plot(inputs, output)
    verification = {
        "source_commit": SOURCE, "source_tree": TREE, "run_id": RUN,
        "source_files_verified": len(provenance["source_files"]),
        "archives_sha256_verified": archived_hashes, "complete_passes": len(summaries),
        "junit_tests_passed": tests, "junit_tests_failed_or_skipped": 0,
        "original_derived_files_replayed_byte_identically": comparisons,
        "exact_ordered_git_identity_fixture_equal_across_passes": True,
        "git_identities_per_pass": len(memberships[0]),
        "extracted_raw_logs_and_junit_privacy_scan": "passed",
        "untraced_activity_counts": "null, not zero",
        "heap_label": "Sampled maximum heap used; not live retained memory",
    }
    (output / "verification.json").write_text(json.dumps(verification, indent=2, sort_keys=True) + "\n")
    return verification


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plots", action="store_true", help="Render SVG figures; requires Matplotlib")
    arguments = parser.parse_args()
    result = replay(arguments.output, arguments.plots)
    print(f"Verified {result['complete_passes']} passes, {result['junit_tests_passed']} passing JUnit cases, "
          f"{result['source_files_verified']} source blobs and "
          f"{result['original_derived_files_replayed_byte_identically']} byte-identical hosted derived files")


if __name__ == "__main__":
    main()
