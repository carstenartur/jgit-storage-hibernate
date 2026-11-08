#!/usr/bin/env python3
"""Reject incomplete or misleading paired publication evidence."""

import copy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "publication_nonregression", Path(__file__).with_name("summarize-publication-nonregression.py")
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublicationNonregressionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "raw").mkdir()
        self.protocol = {
            "schema": "jgit-publication-paired/v1",
            "cell": "independent-1w-0ms",
            "sourceCommit": "a" * 40,
            "sourceTree": "b" * 40,
            "pairStages": [24, 96],
            "warmupIterations": 2,
            "warmupSeconds": 3,
            "measurementIterations": 3,
            "measurementSeconds": 5,
            "policies": ["size", "contention-aware"],
            "order": "odd-size-first-even-contention-first",
            "marginPercent": -5.0,
            "heapMiB": 1024,
            "initialHeapMiB": 256,
            "jdkMajor": 21,
            "jmhVersion": "1.37",
            "layout": "independent",
            "writers": 1,
            "payloadKiB": 256,
            "requestedRttMillis": 0,
            "backend": "postgresql-hikari",
            "writeMode": "stateful",
        }
        self.environment = {
            "sourceCommit": "a" * 40,
            "sourceTree": "b" * 40,
            "javaVersion": "21.0.13",
            "javaVm": "OpenJDK 64-Bit Server VM",
            "javaVmVersion": "21.0.13+9-LTS",
            "availableProcessors": 4,
            "osName": "Linux",
            "osArch": "amd64",
            "osVersion": "6.11.0",
            "cpuMax": "max 100000",
            "memoryMax": "max",
            "cpuModel": "Fixture CPU",
            "cpuAffinity": "0-3",
            "databaseMajorVersion": 17,
            "databaseVersion": "PostgreSQL 17.10",
            "fsync": "on",
            "synchronousCommit": "on",
            "fullPageWrites": "on",
            "storage": "host-bind",
            "fileStoreType": "ext4",
            "rttSamplesMillis": [0.4 + i / 100 for i in range(20)],
            "finalRttSamplesMillis": [0.4 + i / 100 for i in range(20)],
            "startedAt": "2026-11-08T00:00:00Z",
        }
        self.trials = []
        self.add_pairs(1, 25)
        self.flush()

    def add_pairs(self, first, stop):
        origin = datetime(2026, 11, 8, tzinfo=timezone.utc)
        for pair in range(first, stop):
            order = ["size", "contention-aware"] if pair % 2 else ["contention-aware", "size"]
            for position, policy in enumerate(order):
                basename = f"pair-{pair:02d}-{policy}"
                self.trials.append({
                    "pair": pair,
                    "position": position,
                    "policy": policy,
                    "sourceCommit": "a" * 40,
                    "result": f"raw/{basename}.json",
                    "output": f"raw/{basename}.log",
                    "startedAt": (origin + timedelta(minutes=(pair - 1) * 2 + position)).isoformat(),
                    "completedAt": (origin + timedelta(minutes=(pair - 1) * 2 + position, seconds=30)).isoformat(),
                    "checkpointBefore": self.checkpoints(),
                    "checkpointAfter": self.checkpoints(),
                })
                score = 100.0 if policy == "size" else 101.0
                self.write(f"raw/{basename}.json", [self.result(policy, score)])
                (self.root / f"raw/{basename}.log").write_text("# Run complete.\n", encoding="utf-8")

    def result(self, policy, score):
        return {
            "jmhVersion": "1.37",
            "jdkVersion": "21.0.13",
            "vmName": "OpenJDK 64-Bit Server VM",
            "vmVersion": "21.0.13+9-LTS",
            "benchmark": "io.github.carstenartur.jgit.storage.hibernate.benchmark.ConcurrentPublicationBenchmark.publishToDifferentRepositories",
            "mode": "thrpt",
            "threads": 1,
            "forks": 1,
            "warmupIterations": 2,
            "warmupTime": "3 s",
            "measurementIterations": 3,
            "measurementTime": "5 s",
            "jvmArgs": ["-Xms256m", "-Xmx1024m"],
            "params": {
                "backend": "postgresql-hikari",
                "writeMode": "stateful",
                "payloadKiB": "256",
                "payloadMiB": "0",
                "publicationPolicy": policy,
                "deployment": "paired-independent-1w-0ms",
            },
            "primaryMetric": {"score": score, "scoreUnit": "ops/s", "rawData": [[score, score, score]]},
            "secondaryMetrics": {
                "publications": {"score": 1000.0, "scoreUnit": "#", "rawData": [[1000.0, 1000.0, 1000.0]]},
                "prePersistedPublicationSelections": {"score": 0.0, "scoreUnit": "#", "rawData": [[0.0, 0.0, 0.0]]},
                "directPublicationSelections": {"score": 1000.0, "scoreUnit": "#", "rawData": [[1000.0, 1000.0, 1000.0]]},
                "gc.alloc.rate.norm": {"score": 4096.0, "scoreUnit": "B/op", "rawData": [[4096.0, 4096.0, 4096.0]]},
                "packAndRefPublicationDuration:p0.50": {"score": 1000.0, "scoreUnit": "us/op"},
                "packAndRefPublicationDuration:p0.95": {"score": 1500.0, "scoreUnit": "us/op"},
                "packAndRefPublicationDuration:p0.99": {"score": 2000.0, "scoreUnit": "us/op"},
                "packPublicationLockAcquisition:p0.95": {"score": 500.0, "scoreUnit": "us/op"},
                "publicationObservedHeapPeak": {"score": 104857600.0, "scoreUnit": "bytes"},
                "storageTransactions": {"score": 2000.0, "scoreUnit": "#"},
            },
        }

    @staticmethod
    def checkpoints():
        return {
            "num_timed": 1, "num_requested": 0,
            "restartpoints_timed": 0, "restartpoints_req": 0, "restartpoints_done": 0,
            "write_time": 12.4, "sync_time": 0.2, "buffers_written": 37,
            "stats_reset": None,
        }

    def write(self, relative, value):
        (self.root / relative).write_text(json.dumps(value), encoding="utf-8")

    def flush(self):
        self.environment["completedAt"] = (datetime(2026, 11, 8, tzinfo=timezone.utc) + timedelta(minutes=len(self.trials))).isoformat()
        self.write("protocol.json", self.protocol)
        self.write("environment.json", self.environment)
        self.write("trials.json", self.trials)

    def change_result(self, trial_index, change):
        path = self.root / self.trials[trial_index]["result"]
        value = json.loads(path.read_text())
        change(value[0])
        path.write_text(json.dumps(value), encoding="utf-8")

    def test_complete_pairs_are_the_independent_replicates(self):
        result = MODULE.summarize(self.root)
        self.assertEqual(result["pairCount"], 24)
        self.assertEqual(result["forkCount"], 48)
        self.assertAlmostEqual(result["throughputChangePercent"], 1.0)
        self.assertAlmostEqual(result["lowerBoundPercent"], 1.0)
        self.assertTrue(result["nonRegressionEstablished"])
        self.assertEqual(len(result["pairs"]), 24)

    def test_unfavorable_complete_evidence_is_retained_not_rejected(self):
        self.add_pairs(25, 97)
        for i, trial in enumerate(self.trials):
            if trial["policy"] == "contention-aware":
                def regress(item):
                    item["primaryMetric"].update(score=90.0, rawData=[[90.0] * 3])
                self.change_result(i, regress)
        self.flush()
        result = MODULE.summarize(self.root)
        self.assertFalse(result["nonRegressionEstablished"])
        self.assertAlmostEqual(result["throughputChangePercent"], -10.0)
        self.assertEqual(result["pairCount"], 96)
        self.assertEqual(len(result["looks"]), 2)

    def test_first_look_failure_cannot_be_promoted_as_complete(self):
        for i, trial in enumerate(self.trials):
            if trial["policy"] == "contention-aware":
                self.change_result(i, lambda x: x["primaryMetric"].update(score=90.0, rawData=[[90.0] * 3]))
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_no_unplanned_extension_after_a_first_look_pass(self):
        self.add_pairs(25, 97)
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_boundary_equality_does_not_pass(self):
        self.add_pairs(25, 97)
        for i, trial in enumerate(self.trials):
            if trial["policy"] == "contention-aware":
                self.change_result(i, lambda x: x["primaryMetric"].update(score=95.0, rawData=[[95.0] * 3]))
        self.flush()
        result = MODULE.summarize(self.root)
        self.assertFalse(result["nonRegressionEstablished"])

    def test_final_look_keeps_first_pairs_and_reports_both_results(self):
        self.add_pairs(25, 97)
        for i, trial in enumerate(self.trials):
            if trial["pair"] <= 24 and trial["policy"] == "contention-aware":
                score = 85.0 if trial["pair"] % 2 else 110.0
                self.change_result(i, lambda x, score=score: x["primaryMetric"].update(score=score, rawData=[[score] * 3]))
        self.flush()
        result = MODULE.summarize(self.root)
        self.assertFalse(result["looks"][0]["nonRegressionEstablished"])
        self.assertTrue(result["looks"][1]["nonRegressionEstablished"])
        self.assertFalse(result["stoppedEarly"])

    def test_fixed_log_ratio_interval_uses_forks_not_iterations(self):
        ratios = [math.exp(0.02)] * 12 + [math.exp(-0.02)] * 12
        actual = MODULE.paired_interval(ratios, 2.709720420119227)
        expected_half = 2.709720420119227 * math.sqrt((24 * .02 ** 2 / 23) / 24)
        self.assertAlmostEqual(actual["changePercent"], 0.0)
        self.assertAlmostEqual(actual["lowerPercent"], 100 * math.expm1(-expected_half))
        self.assertAlmostEqual(actual["upperPercent"], 100 * math.expm1(expected_half))

    def test_missing_or_duplicate_pair_fails(self):
        self.trials[-1] = copy.deepcopy(self.trials[0])
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_pair_order_cannot_be_relabelled(self):
        self.trials[0]["position"] = 1
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_source_identity_must_match_every_trial(self):
        self.trials[2]["sourceCommit"] = "c" * 40
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_incomplete_iterations_nonfinite_score_and_wrong_coordinate_fail(self):
        cases = [
            lambda x: x["primaryMetric"].update(rawData=[[100.0, 100.0]]),
            lambda x: x["primaryMetric"].update(score=float("nan")),
            lambda x: x["params"].update(payloadKiB="768"),
            lambda x: x.update(threads=4),
            lambda x: x.update(forks=2),
            lambda x: x.update(measurementTime="1 s"),
            lambda x: x["primaryMetric"].update(score=99.0),
        ]
        path = self.root / self.trials[0]["result"]
        original = path.read_text()
        for change in cases:
            with self.subTest(change=change):
                path.write_text(original)
                self.change_result(0, change)
                with self.assertRaises(ValueError):
                    MODULE.summarize(self.root)

    def test_unsafe_durability_and_memory_backed_storage_fail(self):
        for key, value in [("fsync", "off"), ("synchronousCommit", "off"), ("fileStoreType", "tmpfs")]:
            original = self.environment[key]
            self.environment[key] = value
            self.flush()
            with self.subTest(key=key), self.assertRaises(ValueError):
                MODULE.summarize(self.root)
            self.environment[key] = original

    def test_original_fork_output_is_required(self):
        (self.root / self.trials[0]["output"]).unlink()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_protocol_cannot_substitute_for_the_original_log(self):
        (self.root / self.trials[0]["output"]).unlink()
        self.trials[0]["output"] = "protocol.json"
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_unlisted_original_log_cannot_be_silently_excluded(self):
        (self.root / "raw/extra.log").write_text("# Run complete.\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_log_must_belong_to_its_own_raw_result(self):
        self.trials[0]["output"], self.trials[1]["output"] = (
            self.trials[1]["output"], self.trials[0]["output"])
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_cpu_and_runtime_provenance_cannot_be_missing_or_untyped(self):
        original = copy.deepcopy(self.environment)
        for key in ("availableProcessors", "javaVm", "javaVmVersion", "osName", "osArch",
                    "osVersion", "cpuMax", "memoryMax", "cpuModel", "cpuAffinity"):
            with self.subTest(missing=key):
                self.environment = copy.deepcopy(original)
                self.environment.pop(key)
                self.flush()
                with self.assertRaises(ValueError):
                    MODULE.summarize(self.root)
        for key, invalid in (("availableProcessors", 0), ("availableProcessors", True),
                             ("availableProcessors", 1.5), ("cpuMax", ""), ("memoryMax", 3)):
            with self.subTest(key=key, invalid=invalid):
                self.environment = copy.deepcopy(original)
                self.environment[key] = invalid
                self.flush()
                with self.assertRaises(ValueError):
                    MODULE.summarize(self.root)

    def test_unavailable_hardware_markers_are_explicit_and_accepted(self):
        for key in ("cpuMax", "memoryMax", "cpuModel", "cpuAffinity"):
            self.environment[key] = "unavailable"
        self.flush()
        self.assertTrue(MODULE.summarize(self.root)["nonRegressionEstablished"])

    def test_checkpoints_require_complete_typed_nonnegative_observations(self):
        original = copy.deepcopy(self.trials[0])
        cases = [lambda x: x.pop("checkpointBefore"), lambda x: x.pop("checkpointAfter")]
        for key in self.checkpoints():
            cases.append(lambda x, key=key: x["checkpointBefore"].pop(key))
        cases.extend([
            lambda x: x["checkpointAfter"].update(num_timed=-1),
            lambda x: x["checkpointAfter"].update(num_requested=0.5),
            lambda x: x["checkpointAfter"].update(buffers_written=True),
            lambda x: x["checkpointAfter"].update(write_time=-1.0),
            lambda x: x["checkpointAfter"].update(sync_time="unknown"),
            lambda x: x["checkpointAfter"].update(stats_reset="yesterday"),
        ])
        for change in cases:
            with self.subTest(change=change):
                self.trials[0] = copy.deepcopy(original)
                change(self.trials[0])
                self.flush()
                with self.assertRaises(ValueError):
                    MODULE.summarize(self.root)

    def test_checkpoint_activity_and_reset_are_retained_without_selecting_results(self):
        self.trials[0]["checkpointAfter"].update(
            num_requested=200, write_time=5000.2, sync_time=300.0,
            stats_reset="2026-11-08T00:00:10+00:00")
        self.flush()
        self.assertTrue(MODULE.summarize(self.root)["nonRegressionEstablished"])

    def test_actual_fork_runtime_must_match_the_environment_and_protocol(self):
        cases = [lambda x: x.pop("jdkVersion"), lambda x: x.update(jdkVersion="17.0.13"),
                 lambda x: x.update(jdkVersion="21.0.12"), lambda x: x.update(jmhVersion="1.36"),
                 lambda x: x.update(vmName="another VM"), lambda x: x.pop("vmVersion"),
                 lambda x: x.update(vmVersion="21.0.12+7-LTS")]
        path = self.root / self.trials[0]["result"]
        original = path.read_text()
        for change in cases:
            with self.subTest(change=change):
                path.write_text(original)
                self.change_result(0, change)
                with self.assertRaises(ValueError):
                    MODULE.summarize(self.root)

    def test_effective_heap_has_one_initial_and_one_maximum_setting(self):
        cases = [["-Xmx1024m"], ["-Xms256m", "-Xmx1024m", "-Xmx512m"],
                 ["-Xms256m", "-Xmx1024m", "-Xms512m"],
                 ["-Xms256m", "-Xmx1024m", "-Xmx1024m"],
                 ["-Xms256m", "-Xmx1024m", "-XX:MaxHeapSize=512m"],
                 ["-Xms256m", "-Xmx1024m", "-XX:InitialHeapSize=512m"]]
        for args in cases:
            with self.subTest(args=args):
                self.change_result(0, lambda x, args=args: x.update(jvmArgs=args))
                with self.assertRaises(ValueError):
                    MODULE.summarize(self.root)

    def test_latency_and_allocation_observations_cannot_be_omitted(self):
        self.change_result(0, lambda x: x["secondaryMetrics"].pop("packAndRefPublicationDuration:p0.95"))
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_failed_final_calibration_cannot_be_promoted(self):
        self.environment["finalRttSamplesMillis"] = [100.0] * 20
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_overlapping_fork_windows_fail(self):
        self.trials[1]["startedAt"] = self.trials[0]["startedAt"]
        self.flush()
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)

    def test_unexpected_extra_raw_result_cannot_be_silently_excluded(self):
        self.write("raw/inconvenient-extra.json", [self.result("contention-aware", 50.0)])
        with self.assertRaises(ValueError):
            MODULE.summarize(self.root)


if __name__ == "__main__":
    unittest.main()
