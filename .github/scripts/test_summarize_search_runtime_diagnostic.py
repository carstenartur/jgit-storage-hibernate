#!/usr/bin/env python3
"""Semantic regression tests for the separately instrumented runtime diagnostic."""

import importlib.util
import csv
import hashlib
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("summarize-search-runtime-diagnostic.py")
SPEC = importlib.util.spec_from_file_location("runtime_diagnostic", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def row(sequence, message, *, phase="sustained", component="IW", elapsed=None):
    return {
        "seq": str(sequence), "elapsed_ns": str(elapsed if elapsed is not None else sequence * 1000),
        "phase": phase, "thread": "fixture-worker", "component": component, "message": message,
    }


class ActivityParserTest(unittest.TestCase):
    def test_counts_publications_reader_work_and_installed_merges_only(self):
        records = [
            row(1, "commit: start"),
            row(2, "commit: pendingCommit == null; skip"),
            row(3, 'commit: done writing segments file "segments_2"'),
            row(4, "getReader took 4 ms"),
            row(5, "merge codec=Lucene912; 0.1 sec to merge segment [0.1 MB, 1.0 MB/sec]"),
            row(6, "commitMerge: skip: it was aborted"),
            row(7, "after commitMerge: _3(9.12.3):c20"),
            row(8, "getReader took 999 ms", component="OTHER"),
        ]
        events = MODULE.parse_trace(records)
        self.assertEqual(["commit_publication", "nrt_reader_open_work", "merge_installation"],
                         [event["kind"] for event in events])
        self.assertEqual([3, 4, 7], [event["seq"] for event in events])
        self.assertEqual("segments_2", events[0]["detail"])

    def test_preserves_setup_and_close_phase_instead_of_assigning_them_to_sustained(self):
        events = MODULE.parse_trace([
            row(1, 'commit: done writing segments file "segments_1"', phase="setup"),
            row(2, "getReader took 0 ms", phase="sustained"),
            row(3, 'commit: done writing segments file "segments_2"', phase="close"),
        ])
        self.assertEqual(["setup", "sustained", "close"], [event["phase"] for event in events])

    def test_rejects_duplicate_sequence_even_when_message_is_not_an_activity(self):
        with self.assertRaisesRegex(ValueError, "sequence"):
            MODULE.parse_trace([row(1, "commit: start"), row(1, "anything")])

    def test_rejects_backward_clock_and_unknown_phase(self):
        with self.assertRaisesRegex(ValueError, "time"):
            MODULE.parse_trace([row(1, "anything", elapsed=100), row(2, "anything", elapsed=99)])
        with self.assertRaisesRegex(ValueError, "phase"):
            MODULE.parse_trace([row(1, "anything", phase="invented")])

    def test_rejects_duplicate_commit_publication_in_the_same_index(self):
        with self.assertRaisesRegex(ValueError, "segments_2"):
            MODULE.parse_trace([
                row(1, 'commit: done writing segments file "segments_2"'),
                row(2, 'commit: done writing segments file "segments_2"', phase="close"),
            ])


def properties(path, values):
    path.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")


def table(path, columns, rows):
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(columns.split(","))
        for index, values in enumerate(rows, 1):
            writer.writerow([index, *values])


def fixture(root):
    metadata = {
        "schema.version": 1, "source.commit": "a" * 40, "source.tree": "b" * 40,
        "scenario": "sync-async-r500", "resources": "local-correctness", "pass": "trace",
        "trace.enabled": "true", "seed.commits": 10, "batch.commits": 2,
        "sustained.batches": 2, "overload.batches": 10, "period.millis": 10,
        "gate.requested.hold.millis": 100, "heap.max.bytes": 1024,
        "available.processors": 1, "cpu.affinity": "0", "lucene.version": "9.12.3",
        "hibernate.search.version": "8.4.0.Final", "database.product": "HSQL Database Engine",
    }
    properties(root / "metadata.properties", metadata)
    complete = {
        "status": "complete", "expected.commits": 34, "sql.commits": 34,
        "visible.commits": 34, "reopened.sql.commits": 34, "reopened.visible.commits": 34,
        "workers.after.close": 0, "handler.failures": 0, "membership.git.verified": "true",
        "membership.reopen.verified": "true", "gate.entered": "true",
        "gate.producer.incomplete": "true", "gate.queue.backpressure.observed": "true",
        "gate.submitted.commits": 16, "gate.acknowledged.commits": 14,
        "gate.held.ns": 150_000_000, "gate.entered.elapsed.ns": 40_000_000,
        "gate.released.elapsed.ns": 190_000_000,
        "drain.sustained.ns": 1_000_000, "drain.overload.ns": 40_000_000,
    }
    table(root / "transactions.csv", "seq,phase,started_ns,completed_ns,first_index,last_index,commits,deadline_lateness_ns",
          [["sustained", 1_000_000, 3_000_000, 10, 11, 2, 0],
           ["sustained", 11_000_000, 13_000_000, 12, 13, 2, 0]] +
          [["overload", 30_000_000 if i == 0 else 201_000_000 + i * 2_000_000,
            201_000_000 + (i + 1) * 2_000_000, 14 + i * 2, 15 + i * 2, 2, 0] for i in range(10)])
    table(root / "queries.csv", "seq,phase,started_ns,completed_ns,stable_hits,visible_commits,visibility_observed_ns", [
        ["sustained", 1_300_000, 1_400_000, 10, 10, 1_450_000],
        ["overload", 31_000_000, 201_000_000, 10, 16, 201_500_000],
        ["drain-overload", 222_000_000, 223_000_000, 10, 34, 224_000_000],
    ])
    table(root / "samples.csv", "seq,elapsed_ns,phase,submitted_commits,acknowledged_commits,last_visible_commits,visibility_sample_age_ns,queries_completed,heap_used_bytes,gc_count,gc_time_ms,active_workers,gate_entered,producer_wait", [
        [1_500_000, "sustained", 12, 10, 10, 50_000, 1, 256, 0, 0, 2, "false", "runnable"],
        [150_000_000, "overload", 16, 14, 10, 148_550_000, 1, 300, 0, 0, 2, "true", "queue"],
        [230_000_000, "drain-overload", 34, 34, 34, 6_000_000, 3, 256, 1, 2, 1, "true", "done"],
    ])
    phases = [("setup", 0), ("sustained", 1_000_000), ("drain-sustained", 22_000_000),
              ("overload", 30_000_000), ("drain-overload", 200_000_000), ("verify", 240_000_000),
              ("close", 260_000_000), ("reopen", 280_000_000), ("closed", 300_000_000)]
    trace = [[at, phase, "owner", "DIAGNOSTIC", "phase " + phase] for phase, at in phases]
    trace += [[1_100_000, "sustained", "backend", "IW", 'commit: done writing segments file "segments_1"'],
              [1_200_000, "sustained", "query", "IW", "getReader took 0 ms"],
              [5_000_000, "sustained", "merge", "IW", "after commitMerge: _3:c20"],
              [261_000_000, "close", "owner", "IW", 'commit: done writing segments file "segments_2"']]
    trace.sort(key=lambda r: r[0])
    table(root / "trace.csv", "seq,elapsed_ns,phase,thread,component,message", trace)
    complete["trace.records"] = len(trace)
    properties(root / "completion.properties", complete)
    table(root / "membership.csv", "seq,object_id,sql_present,lucene_present,git_reachable,reopened_sql_present,reopened_lucene_present",
          [[f"{i:040x}", *(["true"] * 5)] for i in range(1, 35)])
    rehash(root)


def rehash(root):
    properties(root / "sha256.properties", {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in root.iterdir() if path.is_file() and path.name != "sha256.properties"
    })


class EvidenceValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.directory.name)
        fixture(self.root)

    def tearDown(self):
        self.directory.cleanup()

    def test_valid_trace_keeps_instrumented_latency_and_close_activity_separate(self):
        summary, events = MODULE.validate_evidence(self.root)
        self.assertIn("pass", summary)
        self.assertEqual("trace", summary["pass"])
        self.assertEqual(1, summary["activity_by_phase"]["sustained"]["commit_publication"])
        self.assertEqual(1, summary["activity_by_phase"]["close"]["commit_publication"])
        self.assertEqual(34, summary["verified_commits"])
        self.assertTrue(summary["queue_backpressure_observed"])
        self.assertEqual(4, len(events))

    def test_detects_corrupt_raw_file_before_derivation(self):
        with (self.root / "samples.csv").open("a") as output:
            output.write("corrupted\n")
        with self.assertRaisesRegex(ValueError, "hash"):
            MODULE.validate_evidence(self.root)

    def test_rejects_a_completion_with_live_worker_or_missing_reopen(self):
        for old, new in [("workers.after.close=0", "workers.after.close=1"),
                         ("membership.reopen.verified=true", "membership.reopen.verified=false")]:
            fixture(self.root)
            file = self.root / "completion.properties"
            file.write_text(file.read_text().replace(old, new))
            rehash(self.root)
            with self.assertRaisesRegex(ValueError, "completion"):
                MODULE.validate_evidence(self.root)

    def test_rejects_trace_data_presented_as_untraced_timing(self):
        file = self.root / "metadata.properties"
        file.write_text(file.read_text().replace("pass=trace", "pass=timing")
                        .replace("trace.enabled=true", "trace.enabled=false"))
        rehash(self.root)
        with self.assertRaisesRegex(ValueError, "untraced"):
            MODULE.validate_evidence(self.root)

    def test_rejects_incomplete_transactions_even_with_updated_file_hash(self):
        file = self.root / "transactions.csv"
        file.write_text("\n".join(file.read_text().splitlines()[:-1]) + "\n")
        rehash(self.root)
        with self.assertRaisesRegex(ValueError, "transaction"):
            MODULE.validate_evidence(self.root)

    def test_rejects_missing_queue_evidence_for_async_overload(self):
        file = self.root / "samples.csv"
        file.write_text(file.read_text().replace(",queue", ",runnable"))
        rehash(self.root)
        with self.assertRaisesRegex(ValueError, "queue"):
            MODULE.validate_evidence(self.root)

    def test_rejects_close_event_relabelled_as_sustained_activity(self):
        file = self.root / "trace.csv"
        file.write_text(file.read_text().replace('261000000,close,', '261000000,sustained,'))
        rehash(self.root)
        with self.assertRaisesRegex(ValueError, "phase"):
            MODULE.validate_evidence(self.root)

    def test_export_replays_identically_without_changing_raw_inputs(self):
        before = {path.name: path.read_bytes() for path in self.root.iterdir()}
        summary, events = MODULE.validate_evidence(self.root)
        with tempfile.TemporaryDirectory() as location:
            first, second = pathlib.Path(location) / "first", pathlib.Path(location) / "second"
            MODULE.write_summary(first, summary, events)
            MODULE.write_summary(second, summary, events)
            self.assertEqual({p.name: p.read_bytes() for p in first.iterdir()},
                             {p.name: p.read_bytes() for p in second.iterdir()})
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.root.iterdir()})

    def test_untraced_activity_is_unknown_instead_of_zero_work(self):
        file = self.root / "metadata.properties"
        file.write_text(file.read_text().replace("pass=trace", "pass=timing")
                        .replace("trace.enabled=true", "trace.enabled=false"))
        with (self.root / "trace.csv").open(newline="") as source:
            markers = [record for record in csv.DictReader(source) if record["component"] == "DIAGNOSTIC"]
        table(self.root / "trace.csv", "seq,elapsed_ns,phase,thread,component,message",
              [[record[key] for key in ("elapsed_ns", "phase", "thread", "component", "message")] for record in markers])
        file = self.root / "completion.properties"
        file.write_text(file.read_text().replace("trace.records=13", "trace.records=9"))
        rehash(self.root)
        summary, events = MODULE.validate_evidence(self.root)
        self.assertEqual([], events)
        self.assertIsNone(summary["activity_by_phase"]["sustained"]["merge_installation"])

    def test_native_evidence_requires_observed_postgresql_durability_settings(self):
        file = self.root / "metadata.properties"
        native = file.read_text().replace("resources=local-correctness", "resources=constrained")
        native = native.replace("database.product=HSQL Database Engine", "database.product=PostgreSQL")
        settings = ["fsync", "synchronous_commit", "full_page_writes"]
        correct = native + "".join(f"postgresql.{key}=on\n" for key in settings)
        for key in settings:
            for value in (None, "off"):
                with self.subTest(setting=key, value=value):
                    bad = correct.replace(f"postgresql.{key}=on\n", "" if value is None else f"postgresql.{key}={value}\n")
                    file.write_text(bad)
                    rehash(self.root)
                    with self.assertRaisesRegex(ValueError, "durability"):
                        MODULE.validate_evidence(self.root)
        file.write_text(correct)
        rehash(self.root)
        summary, _ = MODULE.validate_evidence(self.root)
        self.assertEqual({key: "on" for key in settings}, summary["postgresql_durability"])


if __name__ == "__main__":
    unittest.main()
