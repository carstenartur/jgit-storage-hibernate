#!/usr/bin/env python3
"""A controlled implementation comparison requires all four auditable pairs."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "reftable_comparison", Path(__file__).with_name("summarize-publication-reftable-comparison.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ROOT = Path(__file__).resolve().parents[2]


class ReftableComparisonTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "raw").mkdir()
        (self.root / "inputs").mkdir()
        archive = ROOT / "docs/evidence/publication-paired-2026-11-08/archives/publication-nonregression-independent-16w-0ms.zip"
        with zipfile.ZipFile(archive) as retained:
            self.protocol = json.loads(retained.read("protocol.json"))
            self.environment = json.loads(retained.read("environment.json"))
            original = json.loads(retained.read("raw/pair-01-size.json"))[0]
            checkpoint = json.loads(retained.read("trials.json"))[0]["checkpointBefore"]
        self.protocol.update(schema="jgit-reftable-controlled/v1", diagnosticOnly=True,
                             forkVariants=MODULE.ORDER, pairCount=4, policies=["size"], jdkRuntime="21.0.13+9")
        for key in ("pairStages", "marginPercent", "order"):
            del self.protocol[key]
        self.environment.update(finalRttSamplesMillis=self.environment["rttSamplesMillis"],
                                completedAt="2026-11-10T00:00:00Z")
        variants = {}
        for variant, ref, tree, blob in (("baseline", MODULE.PREP.BASELINE, "2" * 40, MODULE.PREP.BASELINE_BLOB),
                                        ("candidate", MODULE.PREP.CANDIDATE, "3" * 40, MODULE.PREP.CANDIDATE_BLOB)):
            path = self.root / f"inputs/{variant}-core.jar"
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr(MODULE.PREP.CORE_CLASS, variant.encode())
                jar.writestr("Other.class", b"identical")
            entries = MODULE.PREP.jar_entries(path)
            variants[variant] = dict(sourceCommit=ref, sourceTree=tree,
                buildCheckoutCommit=ref, buildCheckoutTree=tree,
                coreSourceTree="4" * 40, guardedBlobs={MODULE.PREP.SOURCE_PATH: blob, "pom.xml": "5" * 40},
                jar=f"inputs/{variant}-core.jar", jarSha256=MODULE.PREP.sha(path.read_bytes()),
                classSha256=entries[MODULE.PREP.CORE_CLASS], entries=entries)
        self.inputs = dict(schema="jgit-reftable-variants/v1", coreClass=MODULE.PREP.CORE_CLASS,
                           harnessSourceCommit=self.protocol["sourceCommit"], harnessSourceTree=self.protocol["sourceTree"],
                           sourceDifferences=[MODULE.PREP.SOURCE_PATH], variants=variants,
                           compiledDifferences=MODULE.PREP.validate_jar_difference(variants["baseline"]["entries"], variants["candidate"]["entries"]))
        self.trials, self.outcomes = [], []
        for ordinal, variant in enumerate(MODULE.ORDER, 1):
            pair = (ordinal + 1) // 2
            base = f"raw/pair-{pair:02d}-{variant}"
            selected = variants[variant]
            jarpath = str(self.root / selected["jar"])
            classpath = "/fixed/dependency.jar:" + jarpath + ":/fixed/benchmark-classes"
            args = ["-Xms256m", "-Xmx1024m", "-Djgit.storage.benchmark.connection-properties-file=/tmp/private.properties"]
            args += [prefix + str(self.root / base) + ending + suffix for prefix, ending, suffix in MODULE.GC.LOG_OPTIONS]
            trial = dict(ordinal=ordinal, pair=pair, position=(ordinal - 1) % 2, variant=variant, policy="size",
                         sourceCommit=self.protocol["sourceCommit"], librarySourceCommit=selected["sourceCommit"],
                         jarSha256=selected["jarSha256"], classSha256=selected["classSha256"], classpath=classpath,
                         identity=base + "-identity.json", result=base + ".json", output=base + ".log", jvmArgs=args,
                         startedAt=f"2026-11-09T02:0{ordinal}:00Z", completedAt=f"2026-11-09T02:0{ordinal}:30Z",
                         checkpointBefore=checkpoint, checkpointAfter=checkpoint)
            self.trials.append(trial)
            self.outcomes.append(dict(ordinal=ordinal, variant=variant, status="completed", failureType=None))
            identity = dict(schema="jgit-publication-core-identity/v1", ordinal=ordinal, variant=variant,
                            harnessSourceCommit=self.protocol["sourceCommit"], librarySourceCommit=selected["sourceCommit"],
                            librarySourceTree=selected["sourceTree"], jarPath=jarpath, codeSource=jarpath,
                            jarSha256=selected["jarSha256"], classResource=MODULE.PREP.CORE_CLASS,
                            classSha256=selected["classSha256"], classpath=classpath,
                            resourceUrls=["jar:" + Path(jarpath).as_uri() + "!/" + MODULE.PREP.CORE_CLASS],
                            javaVersion=self.environment["javaVersion"], javaVmVersion=self.environment["javaVmVersion"],
                            actualJvmArgs=args + ["-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
                                                 "-DcompilerBlackholesEnabled=true", "-XX:CompileCommandFile=/tmp/jmh-hints"],
                            maxHeapBytes=1024**3,
                            iterationType="WARMUP", command="org.openjdk.jmh.runner.ForkedMain 127.0.0.1 1234",
                            pid=100 + ordinal, recordedAt=f"2026-11-09T02:0{ordinal}:01Z",
                            output=str(self.root / (base + "-identity.json")),
                            failureOutput=str(self.root / (base + "-identity-failure.json")))
            self.write(trial["identity"], identity)
            result = copy.deepcopy(original)
            result["jvmArgs"] = args
            factor = 0.90 if variant == "candidate" else 1.0
            result["primaryMetric"]["score"] *= factor
            result["primaryMetric"]["rawData"] = [[score * factor for score in result["primaryMetric"]["rawData"][0]]]
            self.write(trial["result"], [result])
            (self.root / trial["output"]).write_text("# Run complete.\n")
            (self.root / (base + "-gc.log")).write_text("[1200ms][info][gc] GC(0) Pause Young (Normal) (GCLocker Initiated GC) 960M->80M(1024M) 1.0ms\n")
            (self.root / (base + "-jni.log")).write_text("[1199ms][debug][gc,jni] Allocation failed. Thread stalled by JNI critical section\n")
            (self.root / (base + "-init.log")).write_text("[1ms][info][gc,init] Heap Region Size: 1M\n")
        self.flush()

    def write(self, name, value):
        (self.root / name).write_text(json.dumps(value))

    def flush(self):
        for name, value in (("protocol", self.protocol), ("environment", self.environment),
                            ("variants", self.inputs), ("trials", self.trials), ("outcomes", self.outcomes)):
            self.write(name + ".json", value)

    def test_retains_a_negative_four_pair_result_without_turning_it_into_acceptance(self):
        result = MODULE.summarize(self.root)
        self.assertEqual(result["status"], "complete-comparison")
        self.assertEqual(len(result["pairs"]), 4)
        self.assertAlmostEqual(result["throughputInterval"]["changePercent"], -10.0)
        self.assertNotIn("nonRegressionEstablished", result)
        self.assertFalse(result["policyQualification"])

    def test_exact_jmh_compiler_blackhole_arguments_are_accepted(self):
        self.assertEqual(MODULE.summarize(self.root)["status"], "complete-comparison")

    def test_generated_jmh_options_cannot_be_partial_duplicated_reordered_or_replaced(self):
        trial = self.trials[0]
        path = self.root / trial["identity"]
        original = json.loads(path.read_text())
        args = original["actualJvmArgs"]
        variants = [args[:-4] + args[-3:], args + [args[-1]],
                    args[:-4] + [args[-3], args[-4]] + args[-2:],
                    args[:-2] + ["-DcompilerBlackholesEnabled=false", args[-1]],
                    args[:-1] + ["-XX:CompileCommandFile=relative-path"],
                    args + ["-Xmx2048m"], args + ["-javaagent:/tmp/hidden.jar"]]
        for changed in variants:
            with self.subTest(args=changed):
                path.write_text(json.dumps(dict(original, actualJvmArgs=changed)))
                with self.assertRaisesRegex(ValueError, "Actual child heap/JVM arguments differ"):
                    MODULE.summarize(self.root)

    def test_failed_attempt_remains_visible_and_prevents_a_four_pair_interval(self):
        self.outcomes[1].update(status="failed", failureType="org.openjdk.jmh.runner.RunnerException")
        trial = self.trials[1]
        (self.root / trial["result"]).write_text("")
        (self.root / trial["output"]).write_text("OutOfMemoryError: Java heap space\n")
        self.flush()
        result = MODULE.summarize(self.root)
        self.assertEqual(result["failedForks"], 1)
        self.assertIsNone(result["throughputInterval"])
        self.assertEqual(len(result["forks"]), 8)

    def test_wrong_loaded_hash_source_or_classpath_is_rejected(self):
        path = self.root / self.trials[0]["identity"]
        original = json.loads(path.read_text())
        for key in ("jarSha256", "classSha256", "codeSource", "librarySourceCommit", "classpath"):
            with self.subTest(key=key):
                wrong = dict(original, **{key: "wrong"})
                path.write_text(json.dumps(wrong))
                with self.assertRaises(ValueError): MODULE.summarize(self.root)
        path.write_text(json.dumps(original))

    def test_actual_child_heap_and_non_core_classpath_cannot_differ(self):
        trial = self.trials[1]
        path = self.root / trial["identity"]
        original = json.loads(path.read_text())
        wrong = dict(original, maxHeapBytes=2 * 1024**3)
        path.write_text(json.dumps(wrong))
        with self.assertRaises(ValueError): MODULE.summarize(self.root)
        wrong = copy.deepcopy(original)
        wrong["actualJvmArgs"].append("-javaagent:/tmp/hidden.jar")
        path.write_text(json.dumps(wrong))
        with self.assertRaises(ValueError): MODULE.summarize(self.root)
        wrong = copy.deepcopy(original)
        wrong["classpath"] = trial["classpath"].replace("dependency.jar", "different.jar")
        trial["classpath"] = wrong["classpath"]
        path.write_text(json.dumps(wrong)); self.flush()
        with self.assertRaises(ValueError): MODULE.summarize(self.root)

    def test_interrupted_partial_run_cannot_use_its_surviving_pair_as_an_interval(self):
        self.trials = self.trials[:2]
        self.outcomes = self.outcomes[:2]
        for path in (self.root / "raw").iterdir():
            if not path.name.startswith("pair-01-"): path.unlink()
        del self.environment["completedAt"]
        del self.environment["finalRttSamplesMillis"]
        self.flush()
        result = MODULE.summarize(self.root)
        self.assertEqual(result["status"], "incomplete-comparison")
        self.assertIsNone(result["throughputInterval"])
        self.assertEqual(result["attemptedForks"], 2)

    def test_missing_or_shadowed_child_proof_is_rejected(self):
        path = self.root / self.trials[0]["identity"]
        original = json.loads(path.read_text())
        path.unlink()
        with self.assertRaises(ValueError): MODULE.summarize(self.root)
        original["resourceUrls"] *= 2
        path.write_text(json.dumps(original))
        with self.assertRaises(ValueError): MODULE.summarize(self.root)

    def test_changed_order_heap_policy_or_extra_attempt_is_rejected(self):
        for change in (lambda: self.protocol.update(pairCount=5),
                       lambda: self.trials[0].update(policy="contention-aware"),
                       lambda: self.trials[0].update(variant="candidate"),
                       lambda: self.trials[0]["jvmArgs"].__setitem__(1, "-Xmx2048m")):
            before = copy.deepcopy((self.protocol, self.trials))
            change(); self.flush()
            with self.assertRaises(ValueError): MODULE.summarize(self.root)
            self.protocol, self.trials = before


if __name__ == "__main__":
    unittest.main()
