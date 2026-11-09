#!/usr/bin/env python3
"""Diagnostic evidence cannot silently become a resumed qualification study."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "publication_gc_diagnostic", Path(__file__).with_name("summarize-publication-gc-diagnostic.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ROOT = Path(__file__).resolve().parents[2]


class PublicationGcDiagnosticTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "raw").mkdir()
        archive = ROOT / "docs/evidence/publication-paired-2026-11-08/archives/publication-nonregression-independent-16w-0ms.zip"
        with zipfile.ZipFile(archive) as original:
            self.protocol = json.loads(original.read("protocol.json"))
            self.environment = json.loads(original.read("environment.json"))
            self.original = json.loads(original.read("raw/pair-01-size.json"))[0]
        self.protocol.update(schema="jgit-publication-gc-diagnostic/v1", diagnosticOnly=True,
                             forkPolicies=["size", "contention-aware", "contention-aware", "size"],
                             originalStudyRun=42431132779, jdkRuntime="21.0.13+9")
        for key in ("pairStages", "marginPercent", "order"):
            del self.protocol[key]
        self.environment.update(finalRttSamplesMillis=self.environment["rttSamplesMillis"],
                                completedAt="2026-11-09T00:00:00Z")
        self.outcomes, self.trials = [], []
        for ordinal, policy in enumerate(self.protocol["forkPolicies"], 1):
            base = f"raw/fork-{ordinal:02d}-{policy}"
            args = ["-Xms256m", "-Xmx1024m",
                    "-Djgit.storage.benchmark.connection-properties-file=/tmp/private.properties",
                    f"-Xlog:gc*=info,gc+heap=debug,gc+humongous=debug,gc+alloc=trace:file={self.root}/{base}-gc.log:uptimemillis,level,tags:filecount=2,filesize=16m",
                    f"-Xlog:gc+jni=debug:file={self.root}/{base}-jni.log:uptimemillis,level,tags:filecount=2,filesize=16m",
                    f"-Xlog:gc+init=info:file={self.root}/{base}-init.log:uptimemillis,level,tags"]
            trial = dict(ordinal=ordinal, policy=policy, sourceCommit=self.protocol["sourceCommit"],
                         result=base + ".json", output=base + ".log", jvmArgs=args,
                         startedAt=f"2026-11-08T02:0{ordinal}:00Z",
                         completedAt=f"2026-11-08T02:0{ordinal}:30Z")
            self.trials.append(trial)
            self.outcomes.append(dict(ordinal=ordinal, policy=policy, status="completed", failureType=None))
            result = copy.deepcopy(self.original)
            result["params"]["publicationPolicy"] = policy
            result["jvmArgs"] = args
            self.write(base + ".json", [result])
            (self.root / (base + ".log")).write_text("# Run complete.\n")
            (self.root / (base + "-gc.log")).write_text(
                "[1200ms][info][gc] GC(0) Pause Young (Normal) (GCLocker Initiated GC) 960M->80M(1024M) 1.0ms\n")
            (self.root / (base + "-jni.log")).write_text("[1199ms][debug][gc,jni] Allocation failed. Thread stalled by JNI critical section\n")
            (self.root / (base + "-init.log")).write_text("[1ms][info][gc,init] Heap Region Size: 1M\n")
        self.flush()

    def write(self, name, value):
        (self.root / name).write_text(json.dumps(value))

    def flush(self):
        self.write("protocol.json", self.protocol)
        self.write("environment.json", self.environment)
        self.write("outcomes.json", self.outcomes)
        self.write("trials.json", self.trials)

    def test_complete_diagnostic_has_no_acceptance_or_policy_comparison(self):
        result = MODULE.summarize(self.root)
        self.assertEqual(result["status"], "complete-diagnostic")
        self.assertTrue(result["diagnosticOnly"])
        self.assertEqual(result["attemptedForks"], 4)
        self.assertEqual(result["failedForks"], 0)
        self.assertNotIn("nonRegressionEstablished", result)
        self.assertNotIn("throughputChangePercent", result)
        self.assertEqual(result["forks"][0]["gcLockerCollections"], 1)
        self.assertEqual(result["forks"][0]["maximumPostGcMiB"], 80)

    def test_completed_fork_rejects_empty_gc_and_initialization_logs(self):
        for suffix in ("-gc.log", "-init.log"):
            path = self.root / ("raw/fork-01-size" + suffix)
            original = path.read_text()
            for contents in ("", " \t\n"):
                with self.subTest(suffix=suffix, contents=contents):
                    try:
                        path.write_text(contents)
                        with self.assertRaisesRegex(ValueError, "required diagnostic logs"):
                            MODULE.summarize(self.root)
                    finally:
                        path.write_text(original)

    def test_empty_jni_log_is_complete_without_allocation_stalls(self):
        path = self.root / "raw/fork-01-size-jni.log"
        for contents in ("", " \t\n"):
            with self.subTest(contents=contents):
                path.write_text(contents)
                result = MODULE.summarize(self.root)
                self.assertEqual(result["status"], "complete-diagnostic")
                self.assertEqual(result["forks"][0]["missingLogKinds"], [])
                self.assertEqual(result["forks"][0]["jniAllocationStalls"], 0)

    def test_failed_fork_with_empty_gc_or_initialization_logs_is_incomplete(self):
        self.outcomes[0].update(status="failed", failureType="org.openjdk.jmh.runner.RunnerException")
        self.flush()
        for suffix in ("-gc.log", "-init.log"):
            with self.subTest(suffix=suffix):
                path = self.root / ("raw/fork-01-size" + suffix)
                original = path.read_text()
                try:
                    path.write_text("")
                    result = MODULE.summarize(self.root)
                    self.assertEqual(result["status"], "incomplete-diagnostic")
                    self.assertEqual(result["forks"][0]["missingLogKinds"], [suffix])
                    self.assertEqual(result["forks"][0]["status"], "failed")
                finally:
                    path.write_text(original)

    def test_empty_active_gc_log_can_use_a_nonempty_rotated_segment(self):
        path = self.root / "raw/fork-01-size-gc.log"
        rotated = path.with_name(path.name + ".0")
        rotated.write_text(path.read_text())
        path.write_text("")
        result = MODULE.summarize(self.root)
        self.assertEqual(result["status"], "complete-diagnostic")
        self.assertEqual(result["forks"][0]["missingLogKinds"], [])
        self.assertEqual(result["forks"][0]["gcCollections"], 1)

    def test_failed_child_stays_failed_and_other_children_are_retained(self):
        self.outcomes[1].update(status="failed", failureType="org.openjdk.jmh.runner.RunnerException")
        trial = self.trials[1]
        (self.root / trial["result"]).write_text("")
        (self.root / trial["output"]).write_text("OutOfMemoryError: Java heap space\nRetried waiting for GCLocker too often allocating 131074 words\n")
        self.flush()
        result = MODULE.summarize(self.root)
        self.assertEqual(result["failedForks"], 1)
        self.assertEqual(result["forks"][1]["status"], "failed")
        self.assertEqual(result["forks"][1]["outOfMemoryErrors"], 1)
        self.assertEqual(result["attemptedForks"], 4)

    def test_partial_sequence_is_reported_as_incomplete(self):
        self.outcomes = self.outcomes[:2]
        self.trials = self.trials[:2]
        del self.environment["completedAt"]
        del self.environment["finalRttSamplesMillis"]
        for path in (self.root / "raw").glob("fork-0[34]-*"):
            path.unlink()
        self.flush()
        self.assertEqual(MODULE.summarize(self.root)["status"], "incomplete-diagnostic")

    def test_reordered_forks_are_rejected(self):
        self.outcomes[1]["policy"] = "size"
        self.flush()
        with self.assertRaisesRegex(ValueError, "order"):
            MODULE.summarize(self.root)

    def test_heap_or_non_logging_runtime_changes_are_rejected(self):
        for option in ("-Xmx2048m", "-XX:GCLockerRetryAllocationCount=100", "-XX:+UseSerialGC"):
            with self.subTest(option=option):
                self.trials[0]["jvmArgs"].append(option)
                self.flush()
                with self.assertRaisesRegex(ValueError, "arguments"):
                    MODULE.summarize(self.root)
                self.trials[0]["jvmArgs"].pop()

    def test_changed_runtime_is_rejected(self):
        self.environment["javaVmVersion"] = "21.0.14+1-LTS"
        self.flush()
        with self.assertRaisesRegex(ValueError, "runtime"):
            MODULE.summarize(self.root)

    def test_rotated_logs_are_included_without_double_counting(self):
        base = "raw/fork-01-size-gc.log.0"
        (self.root / base).write_text("[1100ms][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 900M->64M(1024M) 1.0ms\n")
        fork = MODULE.summarize(self.root)["forks"][0]
        self.assertEqual(fork["gcCollections"], 2)
        self.assertIn(base, fork["logs"])

    def test_allocation_warnings_in_the_gc_file_remain_visible(self):
        path = self.root / "raw/fork-01-size-gc.log"
        with path.open("a") as output:
            output.write("[1201ms][warning][gc,alloc] Retried waiting for GCLocker too often allocating 131074 words\n")
        self.assertEqual(MODULE.summarize(self.root)["forks"][0]["gcLockerRetryWarnings"], 1)

    def test_counterfactual_identity_is_retained_and_unknown_variant_rejected(self):
        self.protocol["variant"] = "reftable"
        self.flush()
        self.assertEqual(MODULE.summarize(self.root)["variant"], "reftable")
        self.protocol["variant"] = "higher-heap"
        self.flush()
        with self.assertRaisesRegex(ValueError, "variant"):
            MODULE.summarize(self.root)


if __name__ == "__main__":
    unittest.main()
