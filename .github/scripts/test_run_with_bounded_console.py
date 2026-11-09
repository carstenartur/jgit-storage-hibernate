#!/usr/bin/env python3
"""Exercise the workflow's actual capture commands with a large binary log producer."""
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


class BoundedConsoleTest(unittest.TestCase):
    def test_build_and_native_capture_retain_all_bytes_and_preserve_exit_status(self):
        root = Path(__file__).parents[2]
        workflow = (root / ".github/workflows/staging-pipeline-evidence.yml").read_text()
        steps = re.split(r"(?m)^      - ", workflow)
        for name, log in (("Build the experiment", "build.log"),
                          ("Collect native transfer", "native.log")):
            step = next(step for step in steps if f"name: {name}" in step)
            command = textwrap.dedent(step.split("        run: |\n", 1)[1])
            for exit_code in (0, 7):
                with self.subTest(step=name, exit_code=exit_code), tempfile.TemporaryDirectory() as temporary:
                    directory = Path(temporary)
                    (directory / "target/staging-pipeline").mkdir(parents=True)
                    scripts = directory / ".github/scripts"
                    scripts.mkdir(parents=True)
                    helper = root / ".github/scripts/run_with_bounded_console.py"
                    if helper.is_file():
                        shutil.copyfile(helper, scripts / helper.name)
                    # Qualification has its own tests; this fixture exercises only the actual capture shell.
                    (scripts / "staging_pipeline_evidence.py").write_text("pass\n")
                    binary = directory / "bin"
                    binary.mkdir()
                    producer = binary / "mvn"
                    producer.write_text(
                        f"#!{sys.executable}\nimport os\n"
                        "os.write(1,b'x'*2_100_000 + '☃'.encode()*100 + b'\\xff'*300)\n"
                        "os.write(2,b'\\nretained stderr\\n')\n"
                        f"raise SystemExit({exit_code})\n")
                    producer.chmod(0o755)
                    environment = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                                       RUNNER_TEMP=str(directory / "runner"), GITHUB_WORKSPACE=str(directory),
                                       GITHUB_SHA="a" * 40, GITHUB_RUN_ID="1", GITHUB_RUN_ATTEMPT="1",
                                       CELL_STORAGE="runner", CELL_PAYLOAD="16", CELL_WINDOW="32",
                                       CELL_WRITERS="1", CELL_RTT="0", CELL_RATE="0", CELL_REPEATS="1",
                                       CELL_WARMUPS="0")
                    result = subprocess.run(["bash", "-euo", "pipefail", "-c", command],
                                            cwd=directory, env=environment,
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
                    self.assertEqual(exit_code, result.returncode)
                    expected = b"x" * 2_100_000 + "☃".encode() * 100 + b"\xff" * 300 + b"\nretained stderr\n"
                    self.assertEqual(expected, (directory / "target/staging-pipeline" / log).read_bytes())
                    self.assertLessEqual(len(result.stdout), 16_384,
                                         "a single multi-MiB line must not bypass the console byte limit")
                    result.stdout.decode("utf-8", errors="strict")

    def test_broken_console_does_not_replace_the_command_failure(self):
        spec = importlib.util.spec_from_file_location(
            "bounded_console", Path(__file__).with_name("run_with_bounded_console.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        class BrokenConsole:
            def write(self, data):
                raise BrokenPipeError("console closed")

        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "native.log"
            result = module.run([sys.executable, "-c", "print('retained');raise SystemExit(7)"],
                                log, 128, BrokenConsole())
            self.assertEqual(7, result)
            self.assertEqual(b"retained\n", log.read_bytes())

    def test_closed_cli_pipe_preserves_exit_status_through_interpreter_shutdown(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "native.log"
            helper = Path(__file__).with_name("run_with_bounded_console.py")
            process = subprocess.Popen(
                [sys.executable, str(helper), "--log", str(log), "--", sys.executable,
                 "-c", "print('retained');raise SystemExit(7)"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            process.stdout.close()
            try:
                self.assertEqual(7, process.wait(timeout=5))
                self.assertEqual(b"retained\n", log.read_bytes())
            finally:
                process.stderr.close()
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
