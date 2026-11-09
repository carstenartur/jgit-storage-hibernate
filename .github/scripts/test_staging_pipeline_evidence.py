#!/usr/bin/env python3
"""Behavioral checks for bounded evidence selection and incomplete artifact rejection."""
import importlib.util
import csv
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

MODULE_PATH = Path(__file__).with_name("staging_pipeline_evidence.py")
SPEC = importlib.util.spec_from_file_location("staging_pipeline_evidence", MODULE_PATH)
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)


class StagingEvidenceTest(unittest.TestCase):
    def test_artifact_upload_blocks_connection_details_even_after_a_failed_build(self):
        root = Path(__file__).parents[2]
        workflow = (root / ".github/workflows/staging-pipeline-evidence.yml").read_text()
        steps = re.split(r"(?m)^      - ", workflow)
        scanner = next((step for step in steps if "--privacy-only" in step), None)
        self.assertIsNotNone(scanner, "retained logs need a privacy scan")
        upload = next(step for step in steps if "actions/upload-artifact@" in step
                      and "target/staging-pipeline/**" in step)
        scanner_id = re.search(r"(?m)^        id: (.+)$", scanner).group(1)
        scanner_condition = re.search(r"(?m)^        if: (.+)$", scanner).group(1)
        upload_condition = re.search(r"(?m)^        if: (.+)$", upload).group(1)
        command = textwrap.dedent(scanner.split("        run: |\n", 1)[1])

        def condition(expression, outcome):
            # Evaluate only this workflow's small Actions condition against a failed earlier build.
            expression = expression.replace("${{", "").replace("}}", "").strip()
            expression = expression.replace("always()", "True").replace("success()", "False")
            expression = expression.replace(f"steps.{scanner_id}.outcome", repr(outcome))
            return eval(expression.replace("&&", " and ").replace("||", " or "),
                        {"__builtins__": {}}, {})

        for outcome in ("failure", "skipped", "cancelled"):
            self.assertFalse(condition(upload_condition, outcome), outcome)
        self.assertTrue(condition(scanner_condition, "skipped"), "scan must run after build failure")
        for contaminated in (None, "target/staging-pipeline/build.log",
                             "target/staging-pipeline/native.log",
                             "jgit-storage-hibernate-benchmarks/target/surefire-reports/TEST-staging.xml"):
            with self.subTest(contaminated=contaminated), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                script = directory / ".github/scripts/validate_search_database_telemetry.py"
                script.parent.mkdir(parents=True)
                shutil.copyfile(root / ".github/scripts/validate_search_database_telemetry.py", script)
                for report in ("target/staging-pipeline/build.log", "target/staging-pipeline/native.log",
                               "jgit-storage-hibernate-benchmarks/target/surefire-reports/TEST-staging.xml"):
                    path = directory / report
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("synthetic diagnostic fixture\n")
                if contaminated:
                    (directory / contaminated).write_text("Database JDBC URL: jdbc:hsqldb:mem:private-fixture\n")
                result = subprocess.run(["bash", "-euo", "pipefail", "-c", command], cwd=directory,
                                        capture_output=True, text=True)
                outcome = "success" if result.returncode == 0 else "failure"
                self.assertEqual(contaminated is None, condition(upload_condition, outcome))

    def test_default_is_small_and_full_matrix_covers_all_requested_coordinates(self):
        self.assertEqual(1, len(evidence.matrix("smoke", 1)["include"]))
        cells = evidence.matrix("full", 3)["include"]
        self.assertEqual(216, len(cells))
        self.assertEqual(216, len({cell["id"] for cell in cells}))
        self.assertEqual({16, 128, 512}, {cell["payload"] for cell in cells})
        self.assertEqual({16, 32}, {cell["window"] for cell in cells})
        self.assertEqual({1, 4, 16}, {cell["writers"] for cell in cells})
        self.assertEqual({0, 5, 20, 50}, {cell["rtt"] for cell in cells})
        self.assertEqual({"runner", "tmpfs", "paced"}, {cell["storage"] for cell in cells})

    def test_coverage_is_bounded_but_includes_large_concurrent_and_storage_controls(self):
        cells = evidence.matrix("coverage", 3)["include"]
        self.assertEqual(8, len(cells))
        self.assertTrue(any(c["payload"] == 512 and c["writers"] == 4 for c in cells))
        self.assertTrue(any(c["payload"] == 16 and c["window"] == 32 for c in cells))
        self.assertEqual({0, 5, 20, 50}, {c["rtt"] for c in cells})
        self.assertEqual({1, 4, 16}, {c["writers"] for c in cells})

    def test_rejects_invalid_execution_requests(self):
        for profile, repeats in [("unknown", 3), ("full", 0), ("coverage", 10)]:
            with self.assertRaises(ValueError):
                evidence.matrix(profile, repeats)

    def test_paced_trial_must_show_the_requested_read_limit_in_measured_time(self):
        rows = [self.trial(mode) for mode in evidence.MODES]
        for row in rows:
            row["read_mib_per_second"] = "150"
        with self.assertRaisesRegex(ValueError, "paced"):
            evidence.validate_trials(rows, 1)

    def test_rejects_missing_mode_and_two_window_bound_violation(self):
        rows = [self.trial(mode) for mode in evidence.MODES]
        evidence.validate_trials(rows, 1)
        with self.assertRaisesRegex(ValueError, "complete"):
            evidence.validate_trials(rows[:-1], 1)
        rows[1]["buffer_bound_bytes"] = str(33 * 1024 * 1024)
        with self.assertRaisesRegex(ValueError, "buffer"):
            evidence.validate_trials(rows, 1)

    def test_rejects_rollback_rows_that_leave_data_or_omit_a_direction(self):
        rows = [dict(sample="1", mode=mode, failure_stage=stage, window_mib="16",
                     payload_bytes=str(128 * 1024 * 1024 + 257), failure_to_return_nanos="20",
                     first_window_flushed_nanos="30", failure_detected_nanos="40",
                     rollback_nanos="10", flushed_chunks="16", remaining_packs="0", remaining_chunks="0")
                for mode in evidence.MODES for stage in ("READER", "JDBC")]
        evidence.validate_rollbacks(rows, 1, 16)
        with self.assertRaisesRegex(ValueError, "complete"):
            evidence.validate_rollbacks(rows[:-1], 1, 16)
        rows[0]["remaining_chunks"] = "1"
        with self.assertRaisesRegex(ValueError, "rollback"):
            evidence.validate_rollbacks(rows, 1, 16)

    def test_rollback_requires_detection_after_the_first_flush(self):
        rows = [dict(sample="1", mode=mode, failure_stage=stage, window_mib="16",
                     payload_bytes=str(128 * 1024 * 1024 + 257), failure_to_return_nanos="20",
                     first_window_flushed_nanos="30", failure_detected_nanos="40",
                     rollback_nanos="10", flushed_chunks="16", remaining_packs="0", remaining_chunks="0")
                for mode in evidence.MODES for stage in ("READER", "JDBC")]
        evidence.validate_rollbacks(rows, 1, 16)
        for field, value in (("failure_detected_nanos", "29"),
                             ("first_window_flushed_nanos", "0"),
                             ("failure_detected_nanos", None)):
            with self.subTest(field=field, value=value):
                invalid = [dict(row) for row in rows]
                if value is None:
                    invalid[0].pop(field)
                else:
                    invalid[0][field] = value
                with self.assertRaisesRegex(ValueError, "flush"):
                    evidence.validate_rollbacks(invalid, 1, 16)

    def test_summary_qualifies_only_complete_native_transfer_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.write_fixture(directory)
            result = evidence.summarize(directory, "a" * 40)
            self.assertEqual(3, result["qualified_transfer_samples"])
            self.assertEqual(6, result["verified_rollback_samples"])
            self.assertFalse(result["full_git_publication_measured"])
            self.assertEqual("keep synchronous", result["production_decision"])
            self.assertTrue((directory / "pipeline.svg").is_file())
            with self.assertRaisesRegex(ValueError, "commit mismatch"):
                evidence.summarize(directory, "b" * 40)
            telemetry = json.loads((directory / "database-telemetry.json").read_text())
            telemetry["observations"][0]["coordinate"]["payloadMiB"] = "512"
            (directory / "database-telemetry.json").write_text(json.dumps(telemetry))
            with self.assertRaisesRegex(ValueError, "coordinate"):
                evidence.summarize(directory)
            telemetry["observations"][0]["coordinate"]["payloadMiB"] = "128"
            telemetry["observations"][0]["counters"]["postgresql.wal.insert_lsn_bytes"] = 0
            (directory / "database-telemetry.json").write_text(json.dumps(telemetry))
            with self.assertRaisesRegex(ValueError, "WAL"):
                evidence.summarize(directory)

    def test_timeline_rejects_incomplete_byte_accounting(self):
        trials = [self.trial(mode) for mode in evidence.MODES]
        timeline = self.timeline()
        evidence.validate_timeline(timeline, trials)
        timeline[0]["bytes"] = "1"
        with self.assertRaisesRegex(ValueError, "bytes"):
            evidence.validate_timeline(timeline, trials)

    @classmethod
    def write_fixture(cls, directory):
        # Deliberately synthetic rows test the validator only; never retained as benchmark evidence.
        (directory / "environment.properties").write_text(
            "measurement_boundary=staged-transfer-through-transaction-commit\n"
            + "source_commit=" + "a" * 40 + "\nstorage=runner\nstaging_filesystem=ext4\n"
            + "rollback_writers=1\nrepeats=1\nwarmup_rounds=0\n")
        def write_csv(name, rows):
            with (directory / name).open("w", newline="") as target:
                writer = csv.DictWriter(target, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)
        write_csv("trials.csv", [cls.trial(mode) for mode in evidence.MODES])
        write_csv("timeline.csv", cls.timeline())
        write_csv("rtt-calibration.csv", [dict(sample=n, rtt_nanos=100000) for n in range(1, 10)])
        write_csv("rollback.csv", [dict(sample=1, mode=mode, failure_stage=stage, window_mib=16,
                                        payload_bytes=128 * 1024 * 1024 + 257, failure_to_return_nanos=20,
                                        first_window_flushed_nanos=30, failure_detected_nanos=40,
                                        rollback_nanos=10, flushed_chunks=16, remaining_packs=0, remaining_chunks=0)
                                   for mode in evidence.MODES for stage in ("READER", "JDBC")])
        (directory / "database-telemetry.json").write_text(json.dumps(dict(observations=[
            dict(coordinate=dict(sample="1", mode=mode, payloadMiB="128", windowMiB="16", writers="1", rttNanos="100000"), backend="postgresql", enabled=True,
                 counters={"postgresql.wal.insert_lsn_bytes": 100}) for mode in evidence.MODES])))

    @staticmethod
    def timeline():
        return [dict(sample="1", mode=mode, writer="0", phase=phase, start_nanos=str(start),
                     duration_nanos=str(duration), bytes=str(byte_count))
                for mode in evidence.MODES for phase, start, duration, byte_count in
                [("staging-read", 0, 20, 128 * 1024 * 1024 + 257),
                 ("jdbc-window", 20, 50, 0), ("reader-wait", 70, 10, 0)]]

    @staticmethod
    def trial(mode):
        payload = 128 * 1024 * 1024 + 257
        return dict(sample="1", mode=mode, payload_bytes=str(payload), window_mib="16", writers="1",
                    requested_rtt_ms="0", rtt_nanos="100000", read_mib_per_second="0", wall_nanos="100",
                    cpu_nanos="50", allocated_bytes="1024", heap_peak_upper_bytes="2048",
                    buffer_bound_bytes=str((16 if mode == "SYNCHRONOUS" else 32) * 1024 * 1024),
                    staging_read_nanos="20", jdbc_window_nanos="50", reader_wait_nanos="10",
                    overlap_nanos="0", staging_written_bytes=str(payload), staging_read_bytes=str(payload))


if __name__ == "__main__":
    unittest.main()
