#!/usr/bin/env python3
"""Validate and summarize the bounded sustained/overload diagnostic.

Event boundaries follow IndexWriter.java in Apache Lucene 9.12.3. In particular,
after commitMerge is emitted after installing the merge and checkpointing; the
earlier merge-codec duration is emitted before commitMerge and is not completion.
"""

import argparse
import collections
import csv
import hashlib
import json
import math
import pathlib
import re
import sys

PHASES = {
    "setup", "sustained", "drain-sustained", "overload", "drain-overload",
    "verify", "close", "reopen", "closed",
}
COMMIT = re.compile(r'commit: done writing segments file "(segments_[0-9a-z]+)"\Z')
READER = re.compile(r"getReader took ([0-9]+) ms\Z")
PHASE_ORDER = ["setup", "sustained", "drain-sustained", "overload", "drain-overload",
               "verify", "close", "reopen", "closed"]
RAW_FILES = {"metadata.properties", "completion.properties", "samples.csv", "transactions.csv",
             "queries.csv", "trace.csv", "membership.csv"}
KINDS = ["commit_publication", "nrt_reader_open_work", "merge_installation"]
HEADERS = {
    "trace.csv": "seq,elapsed_ns,phase,thread,component,message",
    "transactions.csv": "seq,phase,started_ns,completed_ns,first_index,last_index,commits,deadline_lateness_ns",
    "queries.csv": "seq,phase,started_ns,completed_ns,stable_hits,visible_commits,visibility_observed_ns",
    "samples.csv": "seq,elapsed_ns,phase,submitted_commits,acknowledged_commits,last_visible_commits,visibility_sample_age_ns,queries_completed,heap_used_bytes,gc_count,gc_time_ms,active_workers,gate_entered,producer_wait",
    "membership.csv": "seq,object_id,sql_present,lucene_present,git_reachable,reopened_sql_present,reopened_lucene_present",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, label, minimum=0):
    require(re.fullmatch(r"[0-9]+", str(value)) is not None, f"Invalid integer {label}")
    result = int(value)
    require(result >= minimum, f"Out-of-range {label}")
    return result


def read_properties(path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        require(separator and key not in result, f"Malformed or duplicate property in {path.name}")
        value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), value)
        value = re.sub(r"\\(.)", lambda match: {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(match[1], match[1]), value)
        result[key] = value
    return result


def read_table(root, name):
    with (root / name).open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        require(reader.fieldnames == HEADERS[name].split(","), f"Unexpected columns in {name}")
        rows = list(reader)
    require(len(rows) <= 200_000, f"Record limit exceeded in {name}")
    for sequence, row in enumerate(rows, 1):
        require(None not in row and all(value is not None for value in row.values()), f"Incomplete {name}")
        require(integer(row["seq"], "sequence", 1) == sequence, f"Non-contiguous sequence in {name}")
    return rows


def distribution(nanoseconds):
    values = sorted(nanoseconds)
    if not values:
        return {"observations": 0, "min_ms": None, "p50_ms": None, "p95_ms": None,
                "p99_ms": None, "max_ms": None}
    return {"observations": len(values), "min_ms": values[0] / 1e6,
            **{f"p{percentile}_ms": values[math.ceil(len(values) * percentile / 100) - 1] / 1e6
               for percentile in (50, 95, 99)}, "max_ms": values[-1] / 1e6}


def validate_evidence(root):
    root = pathlib.Path(root)
    require((root / "sha256.properties").is_file(), "Missing diagnostic hash manifest")
    hashes = read_properties(root / "sha256.properties")
    require(set(hashes) == RAW_FILES, "Incomplete raw-file hash manifest")
    for name, digest in hashes.items():
        path = root / name
        require(path.is_file() and path.stat().st_size <= 64 * 1024 * 1024, f"Missing or excessive raw file {name}")
        require(hashlib.sha256(path.read_bytes()).hexdigest() == digest, f"Raw hash mismatch: {name}")
    meta = read_properties(root / "metadata.properties")
    done = read_properties(root / "completion.properties")
    require(meta.get("schema.version") == "1", "Unsupported diagnostic schema")
    require(meta.get("lucene.version") == "9.12.3", "Activity parser is pinned to Lucene 9.12.3")
    require(meta.get("scenario") in ("reference", "sync-async-r500"), "Unknown diagnostic scenario")
    traced = meta.get("trace.enabled") == "true"
    require(meta.get("trace.enabled") in ("true", "false") and meta.get("pass") == ("trace" if traced else "timing"),
            "Trace/timing scope mismatch")
    seed = integer(meta["seed.commits"], "seed commits", 1)
    batch = integer(meta["batch.commits"], "batch commits", 1)
    sustained = integer(meta["sustained.batches"], "sustained batches", 2)
    overloaded = integer(meta["overload.batches"], "overload batches", 1)
    expected = seed + batch * (sustained + overloaded)
    require(expected <= 20_000, "Fixture document bound exceeded")
    require(done.get("status") == "complete" and done.get("workers.after.close") == "0"
            and done.get("handler.failures") == "0"
            and done.get("membership.git.verified") == done.get("membership.reopen.verified") == "true",
            "Diagnostic completion/cleanup/reopen evidence is incomplete")
    for key in ("expected.commits", "sql.commits", "visible.commits", "reopened.sql.commits", "reopened.visible.commits"):
        require(integer(done[key], key) == expected, f"Diagnostic completion differs: {key}")
    require(done.get("gate.entered") == done.get("gate.producer.incomplete") == "true", "Overload gate was not proved")
    entered = integer(done["gate.entered.elapsed.ns"], "gate entered")
    released = integer(done["gate.released.elapsed.ns"], "gate released")
    held = integer(done["gate.held.ns"], "gate held", 1)
    require(released - entered == held and held >= integer(meta["gate.requested.hold.millis"], "hold", 1) * 1_000_000,
            "Gate duration is inconsistent")
    require(seed <= integer(done["gate.acknowledged.commits"], "gated acknowledgements")
            < integer(done["gate.submitted.commits"], "gated submission") <= expected, "No in-flight submission at held gate")
    trace = read_table(root, "trace.csv")
    require(len(trace) == integer(done["trace.records"], "trace records"), "Incomplete trace record count")
    events = parse_trace(trace)
    markers = [row for row in trace if row["component"] == "DIAGNOSTIC"]
    require([row["phase"] for row in markers] == PHASE_ORDER
            and all(row["message"] == "phase " + row["phase"] for row in markers), "Incomplete phase boundaries")
    current_phase = None
    for record in trace:
        if record["component"] == "DIAGNOSTIC":
            current_phase = record["phase"]
        require(record["phase"] == current_phase, "Activity phase differs from the active phase marker")
    times = {row["phase"]: int(row["elapsed_ns"]) for row in markers}
    require(all(a < b for a, b in zip(times.values(), list(times.values())[1:])), "Phase time does not advance")
    require(times["overload"] <= entered < released <= times["drain-overload"], "Gate timestamps are outside overload phase")
    if traced:
        require(set(event["kind"] for event in events) == set(KINDS), "Trace lacks real commit/reader/merge activity")
    else:
        require(not events and all(row["component"] == "DIAGNOSTIC" for row in trace), "Lucene tracing present in untraced pass")

    transactions = read_table(root, "transactions.csv")
    require(len(transactions) == sustained + overloaded, "Incomplete transaction matrix")
    next_index = seed
    previous_end = -1
    latency = collections.defaultdict(list)
    for number, row in enumerate(transactions):
        phase = "sustained" if number < sustained else "overload"
        started = integer(row["started_ns"], "transaction start")
        end = integer(row["completed_ns"], "transaction end", 1)
        require(row["phase"] == phase and started >= previous_end and end > started
                and end <= times["verify"], "Invalid transaction phase or timing")
        require(integer(row["first_index"], "first index") == next_index
                and integer(row["last_index"], "last index") == next_index + batch - 1
                and integer(row["commits"], "transaction commits") == batch, "Non-contiguous transaction membership")
        integer(row["deadline_lateness_ns"], "submission schedule lateness")
        latency[phase].append(end - started)
        next_index += batch
        previous_end = end
    sustained_ns = times["drain-sustained"] - times["sustained"]
    require(sustained_ns >= (sustained - 1) * integer(meta["period.millis"], "period", 1) * 1_000_000,
            "Sustained phase did not span the paced transaction schedule")

    queries = read_table(root, "queries.csv")
    query_latency = collections.defaultdict(list)
    previous_end = -1
    for row in queries:
        phase = row["phase"]
        started = integer(row["started_ns"], "query start")
        end = integer(row["completed_ns"], "query completion")
        observed = integer(row["visibility_observed_ns"], "visibility observation")
        require(phase in PHASES and started >= previous_end and end >= started and observed >= end,
                "Invalid query phase/timing")
        require(integer(row["stable_hits"], "stable hits") == min(10, seed), "Stable query membership changed")
        require(seed <= integer(row["visible_commits"], "visible commits") <= expected, "Invalid visibility sample")
        query_latency[phase].append(end - started)
        previous_end = observed
    require(query_latency["sustained"], "No concurrent queries during sustained publication")

    samples = read_table(root, "samples.csv")
    require(samples, "Missing resource/progress samples")
    heap_max = integer(meta["heap.max.bytes"], "heap budget", 1)
    last_time = -1
    max_heap = max_lag = max_age = 0
    queue_seen = False
    previous_counts = [seed, seed, seed, 0, 0, 0]
    for row in samples:
        now = integer(row["elapsed_ns"], "sample time")
        require(now >= last_time and row["phase"] in PHASES, "Resource sample time/phase invalid")
        last_time = now
        submitted = integer(row["submitted_commits"], "submitted commits")
        acknowledged = integer(row["acknowledged_commits"], "acknowledged commits")
        visible = integer(row["last_visible_commits"], "last visible commits")
        query_count = integer(row["queries_completed"], "queries completed")
        gc_count = integer(row["gc_count"], "GC count")
        gc_time = integer(row["gc_time_ms"], "GC time")
        values = [submitted, acknowledged, visible, query_count, gc_count, gc_time]
        require(all(a <= b for a, b in zip(previous_counts, values)), "Progress counter moved backwards")
        previous_counts = values
        require(seed <= acknowledged <= submitted <= expected and seed <= visible <= submitted
                and query_count <= len(queries), "Invalid submission/visibility progress")
        heap = integer(row["heap_used_bytes"], "sampled heap")
        require(heap <= heap_max and integer(row["active_workers"], "active workers") <= 2, "Resource bound exceeded")
        age = integer(row["visibility_sample_age_ns"], "visibility sample age")
        require(age <= now, "Visibility sample precedes the invocation")
        max_heap, max_lag, max_age = max(max_heap, heap), max(max_lag, max(0, acknowledged - visible)), max(max_age, age)
        queue_seen |= row["phase"] == "overload" and row["producer_wait"] == "queue" and row["gate_entered"] == "true"
    require(queue_seen == (done.get("gate.queue.backpressure.observed") == "true"), "Queue observation does not match raw queue samples")
    if meta["scenario"] == "sync-async-r500":
        require(queue_seen, "Async overload did not demonstrate blocked queue submission")

    membership = read_table(root, "membership.csv")
    require(len(membership) == expected, "Incomplete membership records")
    ids = [row["object_id"] for row in membership]
    require(len(set(ids)) == expected and all(re.fullmatch(r"[0-9a-f]{40}", oid) and oid != "0" * 40 for oid in ids),
            "Invalid or repeated Git object identity")
    require(all(all(value == "true" for key, value in row.items() if key not in ("seq", "object_id")) for row in membership),
            "SQL/Lucene/Git/reopen membership is not complete")

    if meta["resources"] in ("normal", "constrained"):
        processors, heap_budget = (4, 1024 * 1024 * 1024) if meta["resources"] == "normal" else (1, 512 * 1024 * 1024)
        require(integer(meta["available.processors"], "CPU budget", 1) == processors and heap_max <= heap_budget,
                "Hosted CPU/heap resource profile was not applied")
        require(meta["database.product"] == "PostgreSQL", "Hosted diagnostic must use PostgreSQL")
    durability = None
    if meta.get("database.product") == "PostgreSQL":
        durability = {key: meta.get("postgresql." + key) for key in ("fsync", "synchronous_commit", "full_page_writes")}
        require(all(value == "on" for value in durability.values()),
                "Missing or disabled observed PostgreSQL durability setting")
    activity = {phase: {kind: 0 if traced else None for kind in KINDS} for phase in PHASE_ORDER}
    for event in events:
        activity[event["phase"]][event["kind"]] += 1
    summary = {
        "schema_version": 1, "source_commit": meta["source.commit"], "source_tree": meta["source.tree"],
        "scenario": meta["scenario"], "resources": meta["resources"], "pass": meta["pass"],
        "timing_scope": "instrumented diagnostic" if traced else "untraced diagnostic; includes sampling",
        "postgresql_durability": durability,
        "verified_commits": expected, "transaction_count": len(transactions), "query_count": len(queries),
        "sustained_commits_per_second": batch * sustained * 1e9 / sustained_ns,
        "publication_latency": {phase: distribution(values) for phase, values in latency.items()},
        "stable_query_latency_by_start_phase": {phase: distribution(query_latency[phase]) for phase in PHASE_ORDER},
        "activity_capture_enabled": traced, "activity_by_phase": activity,
        "sampled_heap_max_bytes": max_heap, "heap_limit_bytes": heap_max,
        "max_acknowledged_minus_last_observed_visible": max_lag,
        "max_visibility_observation_age_ms": max_age / 1e6,
        "queue_backpressure_observed": queue_seen, "gate_held_ms": held / 1e6,
        "sustained_drain_ms": integer(done["drain.sustained.ns"], "sustained drain") / 1e6,
        "overload_drain_ms": integer(done["drain.overload.ns"], "overload drain") / 1e6,
        "raw_sha256": hashes,
    }
    return summary, events


def parse_trace(records):
    events = []
    previous_time = -1
    publications = set()
    for expected, record in enumerate(records, 1):
        sequence = int(record["seq"])
        if sequence != expected:
            raise ValueError(f"Trace sequence {sequence}; expected {expected}")
        elapsed = int(record["elapsed_ns"])
        if elapsed < 0 or elapsed < previous_time:
            raise ValueError("Trace time is negative or moves backwards")
        previous_time = elapsed
        phase = record["phase"]
        if phase not in PHASES:
            raise ValueError(f"Unknown trace phase {phase}")
        if record["component"] != "IW":
            continue
        message = record["message"]
        commit = COMMIT.fullmatch(message)
        reader = READER.fullmatch(message)
        if commit:
            kind, detail = "commit_publication", commit[1]
            if detail in publications:
                raise ValueError(f"Duplicate commit publication {detail}")
            publications.add(detail)
        elif reader:
            kind, detail = "nrt_reader_open_work", reader[1]
        elif message.startswith("after commitMerge: "):
            kind, detail = "merge_installation", message.removeprefix("after commitMerge: ")
        else:
            continue
        events.append({"seq": sequence, "elapsed_ns": elapsed, "phase": phase,
                       "thread": record["thread"], "kind": kind, "detail": detail})
    return events


def write_summary(output, summary, events):
    output = pathlib.Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    with (output / "activity-events.csv").open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=["seq", "elapsed_ns", "phase", "thread", "kind", "detail"])
        writer.writeheader()
        writer.writerows(events)
    lines = ["# Sustained Search runtime diagnostic", "",
             f"Source: `{summary['source_commit']}`; tree `{summary['source_tree']}`.", "",
             f"Scenario: `{summary['scenario']}`; resources: `{summary['resources']}`; pass: **{summary['pass']}**.", "",
             f"Timing scope: **{summary['timing_scope']}**. These observations are not a JMH ranking or a default recommendation.", "",
             "PostgreSQL durability settings (observed with SHOW): " +
             (", ".join(f"`{key}={value}`" for key, value in summary["postgresql_durability"].items())
              if summary["postgresql_durability"] else "not applicable to this local fixture") + ".", "",
             f"Verified {summary['verified_commits']:,} Git/SQL/Lucene identities before and after persistent reopen; "
             f"{summary['transaction_count']} incremental transactions and {summary['query_count']} stable-query samples.", "",
             "| Observation | Value |", "| --- | ---: |",
             f"| Sustained achieved commits/s | {summary['sustained_commits_per_second']:.3f} |",
             f"| Sampled maximum live heap (MiB) | {summary['sampled_heap_max_bytes'] / 1024**2:.3f} |",
             f"| Configured JVM heap limit (MiB) | {summary['heap_limit_bytes'] / 1024**2:.0f} |",
             f"| Injected output gate held (ms) | {summary['gate_held_ms']:.3f} |",
             f"| Sustained visibility drain (ms) | {summary['sustained_drain_ms']:.3f} |",
             f"| Post-gate producer/visibility drain (ms) | {summary['overload_drain_ms']:.3f} |",
             f"| Largest acknowledged minus last observed visible count | {summary['max_acknowledged_minus_last_observed_visible']} |",
             f"| Maximum visibility sample age (ms) | {summary['max_visibility_observation_age_ms']:.3f} |",
             f"| Blocked backend queue submission observed | {str(summary['queue_backpressure_observed']).lower()} |", "",
             "The heap statistic is a sample maximum, not a proof of the instantaneous peak. Visibility differences use "
             "the last completed query and are not internal queue depths. A query blocked at the gate can make that observation stale.", "",
             "## Latency observations", "",
             "Publication timings include advancing the Git ref and invoking the incremental indexer. Stable-query timings "
             "exclude the subsequent visibility-count query. Percentiles use nearest-rank individual observations, without confidence intervals.", "",
             "| Operation/phase | Observations | p50 ms | p95 ms | p99 ms |", "| --- | ---: | ---: | ---: | ---: |"]
    for phase, item in summary["publication_latency"].items():
        lines.append(f"| Publication {phase} | {item['observations']} | {item['p50_ms']:.3f} | {item['p95_ms']:.3f} | {item['p99_ms']:.3f} |")
    for phase, item in summary["stable_query_latency_by_start_phase"].items():
        if item["observations"]:
            lines.append(f"| Query starting in {phase} | {item['observations']} | {item['p50_ms']:.3f} | {item['p95_ms']:.3f} | {item['p99_ms']:.3f} |")
    lines += ["", "## Separately attributed activity", "",
              "A commit publication is Lucene's segments-file publication event, not a caller acknowledgement. "
              "Reader work is completion of an NRT `getReader` operation, not proof that Hibernate Search published a changed reader. "
              "A merge installation is the successful writer segment-state update after `commitMerge`, not a durable commit. "
              "A merge can start in one phase and be installed in another; this table attributes the installation timestamp, without inferring its duration.", "",
              "| Event phase | Commit publications | NRT reader-open work | Merge installations |", "| --- | ---: | ---: | ---: |"]
    for phase, counts in summary["activity_by_phase"].items():
        values = ["unobserved" if counts[kind] is None else str(counts[kind]) for kind in KINDS]
        lines.append("| " + " | ".join([phase, *values]) + " |")
    if summary["pass"] == "timing":
        lines += ["", "Activity tracing was disabled in this pass. Null activity counts mean unobserved activity, not absence of activity."]
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--require-source", help="Require this exact checkout SHA for hosted publication")
    args = parser.parse_args()
    require(args.output.resolve() != args.evidence.resolve(), "Output must be separate from raw evidence")
    summary, events = validate_evidence(args.evidence)
    if args.require_source:
        require(re.fullmatch(r"[0-9a-f]{40}", args.require_source) is not None
                and summary["source_commit"] == args.require_source, "Measured source does not match hosted checkout")
        require(re.fullmatch(r"[0-9a-f]{40}", summary["source_tree"]) is not None, "Missing exact measured source tree")
    write_summary(args.output, summary, events)
    print(f"Verified {summary['scenario']}/{summary['resources']}/{summary['pass']}: "
          f"{summary['verified_commits']} identities, {summary['transaction_count']} transactions, {len(events)} observed activity events")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, csv.Error) as error:
        print(f"Diagnostic evidence rejected: {error}", file=sys.stderr)
        raise SystemExit(1)
