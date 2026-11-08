#!/usr/bin/env python3
"""Exercise diagnostic exit reporting and whole-process-group timeout cleanup."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest

SPEC = importlib.util.spec_from_file_location(
    "diagnostic", Path(__file__).with_name("run_staging_pipeline_diagnostic.py"))
diagnostic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostic)


class DiagnosticRunnerTest(unittest.TestCase):
    def test_successful_leader_exit_cleans_surviving_descendant(self):
        self.assert_leader_exit_cleans_descendant(0)

    def test_failed_leader_exit_cleans_surviving_descendant(self):
        self.assert_leader_exit_cleans_descendant(7)

    def assert_leader_exit_cleans_descendant(self, exit_code):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identity = root / "child.json"
            child = (
                "import json,os,pathlib,signal,time\n"
                "signal.signal(signal.SIGTERM,signal.SIG_IGN)\n"
                "os.write(1,b'child heartbeat\\n')\n"
                f"pathlib.Path({str(identity)!r}).write_text(json.dumps(dict(group=os.getpgrp())))\n"
                "while True:\n os.write(1,b'child heartbeat\\n')\n time.sleep(.01)\n")
            parent = (
                "import pathlib,subprocess,sys,time\n"
                f"child=subprocess.Popen([sys.executable,'-c',{child!r}])\n"
                f"ready=pathlib.Path({str(identity)!r})\n"
                "deadline=time.monotonic()+2\n"
                "while not ready.exists() and time.monotonic()<deadline: time.sleep(.01)\n"
                "assert ready.exists()\n"
                f"raise SystemExit({exit_code})\n")
            try:
                started = time.monotonic()
                result = diagnostic.run([sys.executable, "-c", parent], root,
                                        timeout=5, dump_after=2, grace=.1)
                self.assertEqual(exit_code, result)
                self.assertLess(time.monotonic() - started, 5)
                self.assertIn("child heartbeat", (root / "native.log").read_text())
                before = (root / "native.log").stat().st_size
                time.sleep(.1)
                self.assertEqual(before, (root / "native.log").stat().st_size,
                                 "a child must stop emitting heartbeats when its leader exits")
                status = json.loads((root / "diagnostic-status.json").read_text())
                self.assertFalse(status["timed_out"])
                self.assertEqual(exit_code, status["return_code"])
                self.assertEqual(exit_code, status["command_return_code"])
                self.assertIsNone(status["termination_signal"])
                self.assertEqual("SIGKILL", status["process_group_cleanup_signal"])
            finally:
                # Keep the fail-first regression from leaking its deliberately surviving child.
                if identity.is_file():
                    group = json.loads(identity.read_text())["group"]
                    self.assertNotEqual(os.getpgrp(), group)
                    try:
                        os.killpg(group, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_retains_output_and_real_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = diagnostic.run([sys.executable, "-c", "print('diagnostic fixture');raise SystemExit(7)"],
                                    root, timeout=5, dump_after=2, grace=.1)
            self.assertEqual(7, result)
            self.assertIn("diagnostic fixture", (root / "native.log").read_text())
            status = json.loads((root / "diagnostic-status.json").read_text())
            self.assertTrue(status["diagnostic_only"])
            self.assertFalse(status["timed_out"])
            self.assertEqual(7, status["return_code"])
            self.assertIsNone(status["process_group_cleanup_signal"])

    def test_timeout_kills_an_uncooperative_descendant_and_preserves_partial_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child_file = root / "child.pid"
            child = ("import os,signal,time\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\n"
                     "while True:\n os.write(1,b'child heartbeat\\n')\n time.sleep(.01)\n")
            parent = (
                "import pathlib,signal,subprocess,sys,time;"
                "signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                f"child=subprocess.Popen([sys.executable,'-c',{child!r}]);"
                f"pathlib.Path({str(child_file)!r}).write_text(str(child.pid));"
                "print('partial diagnostic output',flush=True);time.sleep(60)")
            started = time.monotonic()
            result = diagnostic.run([sys.executable, "-c", parent], root,
                                    timeout=.5, dump_after=.2, grace=.1)
            self.assertEqual(124, result)
            self.assertLess(time.monotonic() - started, 5)
            status = json.loads((root / "diagnostic-status.json").read_text())
            self.assertTrue(status["timed_out"])
            self.assertEqual("SIGKILL", status["termination_signal"])
            self.assertIn("partial diagnostic output", (root / "native.log").read_text())
            self.assertTrue(child_file.is_file())
            before = (root / "native.log").stat().st_size
            time.sleep(.1)
            self.assertEqual(before, (root / "native.log").stat().st_size,
                             "the uncooperative child must stop emitting heartbeats after group kill")


if __name__ == "__main__":
    unittest.main()
