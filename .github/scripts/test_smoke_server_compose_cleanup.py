#!/usr/bin/env python3
"""Regression contract for publication-smoke cleanup environment propagation."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
SMOKE_SCRIPT = ROOT / ".github/scripts/smoke_server_compose.sh"


class PublicationSmokeCleanupContractTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = SMOKE_SCRIPT.read_text(encoding="utf-8")
        invocation = "\npersist_publication_cleanup_environment\n"
        invocation_end = cls.text.index(invocation) + len(invocation)
        cls.harness = cls.text[:invocation_end] + "\nexit 0\n"

    def run_harness(
        self, workspace: Path, relative_script: Path
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        script = workspace / relative_script
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(self.harness, encoding="utf-8")
        github_env = workspace / "github-env"
        env = os.environ.copy()
        env.update(
            {
                "GITHUB_ACTIONS": "true",
                "GITHUB_WORKSPACE": str(workspace),
                "GITHUB_ENV": str(github_env),
                "JSH_ADMIN_USERNAME": "admin",
                "JSH_ADMIN_PASSWORD": "publication-admin",
                "JSH_DATABASE_PASSWORD": "publication-database",
                "JSH_SERVER_IMAGE": "example.invalid/server@sha256:" + "a" * 64,
                "SMOKE_REPOSITORY": "publication-smoke",
            }
        )
        result = subprocess.run(
            ["bash", str(script)],
            cwd=workspace,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        return result, github_env

    def test_nested_publication_smoke_exports_required_compose_inputs(self) -> None:
        for fragment in (
            "persist_publication_cleanup_environment()",
            '[[ "${GITHUB_ACTIONS:-}" == true ]] || return 0',
            '[[ -n "${GITHUB_WORKSPACE:-}" ]] || return 0',
            '[[ -n "${GITHUB_ENV:-}" ]] || return 0',
            '[[ -d "$GITHUB_WORKSPACE/publication-tooling" ]] || return 0',
            "${BASH_SOURCE[0]}",
            '[[ "$script_root" == "$publication_root" ]] || return 0',
            "value=${!variable-}",
            "must be one line",
            "printf '%s=%s\\n' \"$variable\" \"$value\" >> \"$GITHUB_ENV\"",
        ):
            self.assertIn(fragment, self.text)

        variable_list_start = self.text.index("for variable in")
        variable_list_end = self.text.index("; do", variable_list_start)
        variable_list = self.text[variable_list_start:variable_list_end]
        for variable in (
            "JSH_ADMIN_USERNAME",
            "JSH_ADMIN_PASSWORD",
            "JSH_DATABASE_PASSWORD",
            "JSH_SERVER_IMAGE",
        ):
            with self.subTest(variable=variable):
                self.assertIn(variable, variable_list)

    def test_nested_script_exports_when_caller_stays_in_outer_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            workspace = Path(temporary_directory) / "workspace"
            workspace.mkdir()
            result, github_env = self.run_harness(
                workspace,
                Path("publication-tooling/.github/scripts/smoke_server_compose.sh"),
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [
                    "JSH_ADMIN_USERNAME=admin",
                    "JSH_ADMIN_PASSWORD=publication-admin",
                    "JSH_DATABASE_PASSWORD=publication-database",
                    "JSH_SERVER_IMAGE=example.invalid/server@sha256:" + "a" * 64,
                ],
                github_env.read_text(encoding="utf-8").splitlines(),
            )

    def test_regular_script_does_not_export_for_a_nested_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            workspace = Path(temporary_directory) / "workspace"
            (workspace / "publication-tooling").mkdir(parents=True)
            result, github_env = self.run_harness(
                workspace, Path(".github/scripts/smoke_server_compose.sh")
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(github_env.exists())

    def test_publication_values_use_the_loop_validation(self) -> None:
        self.assertNotIn("${JSH_DATABASE_PASSWORD:?", self.text)
        self.assertNotIn("${JSH_SERVER_IMAGE:?", self.text)
        self.assertIn("must not be empty", self.text)

    def test_environment_is_persisted_before_any_compose_failure_path(self) -> None:
        invocation = "\npersist_publication_cleanup_environment\n\nwork="
        self.assertIn(invocation, self.text)
        self.assertLess(
            self.text.index(invocation),
            self.text.index("wait_until_ready()"),
        )

    def test_detection_does_not_depend_on_the_callers_working_directory(self) -> None:
        self.assertNotIn('[[ "$PWD" ==', self.text)
        self.assertLess(
            self.text.index("${BASH_SOURCE[0]}"),
            self.text.index("for variable in"),
        )
        self.assertNotIn(".env", self.text)


if __name__ == "__main__":
    unittest.main()
