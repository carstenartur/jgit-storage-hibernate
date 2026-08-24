#!/usr/bin/env python3
"""Regression tests for explicit checks on release-generated pull requests."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


SCRIPT = Path(__file__).with_name("dispatch-generated-pr-checks.sh")
WORKFLOWS = (
    "maven.yml",
    "bom-contract.yml",
    "jgit-compatibility.yml",
    "consumer-compatibility.yml",
    "server-image.yml",
    "server-image-publish-contract.yml",
    "performance.yml",
)
HEAD = "0123456789abcdef0123456789abcdef01234567"


class GeneratedPrCheckDispatchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / ".github/workflows").mkdir(parents=True)
        for workflow in WORKFLOWS:
            (self.root / ".github/workflows" / workflow).write_text(
                "name: test\n", encoding="utf-8"
            )

        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "gh-calls.txt"
        self._write_executable(
            "git",
            f"""
            #!/usr/bin/env bash
            set -euo pipefail
            if [[ "$1 $2" == 'rev-parse HEAD' ]]; then
              printf '%s\\n' '{HEAD}'
            elif [[ "$1" == 'ls-remote' ]]; then
              printf '%s\\t%s\\n' '{HEAD}' "${{@: -1}}"
            else
              printf 'unexpected git call: %s\\n' "$*" >&2
              exit 91
            fi
            """,
        )
        self._write_executable(
            "gh",
            f"""
            #!/usr/bin/env bash
            set -euo pipefail
            printf '%s\\n' "$*" >> '{self.calls}'
            """,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_executable(self, name: str, source: str) -> None:
        path = self.bin / name
        path.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
        path.chmod(0o755)

    def _run(self, branch: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin}{os.pathsep}{env['PATH']}",
                "GH_TOKEN": "test-token-not-printed",
            }
        )
        return subprocess.run(
            ["bash", str(SCRIPT), branch],
            cwd=self.root,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_dispatches_release_checks_for_exact_remote_head(self) -> None:
        result = self._run("release/prepare-0.11.2")
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls.read_text(encoding="utf-8").splitlines()
        self.assertEqual(
            [
                f"workflow run {workflow} --ref release/prepare-0.11.2"
                for workflow in WORKFLOWS
            ],
            calls,
        )
        self.assertNotIn("test-token-not-printed", result.stdout + result.stderr)

    def test_dispatches_next_development_checks(self) -> None:
        result = self._run("release/next-0.11.3")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_arbitrary_branches(self) -> None:
        result = self._run("main")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unexpected branch", result.stderr + result.stdout)
        self.assertFalse(self.calls.exists())


if __name__ == "__main__":
    unittest.main()
