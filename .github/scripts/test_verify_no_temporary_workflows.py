#!/usr/bin/env python3
"""Regression tests for temporary workflow merge protection."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-no-temporary-workflows.py")
SPEC = importlib.util.spec_from_file_location("verify_no_temporary_workflows", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
VERIFY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFY
SPEC.loader.exec_module(VERIFY)


class TemporaryWorkflowVerificationTest(unittest.TestCase):

    def test_clean_workflow_directory_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflows = root / ".github" / "workflows"
            workflows.mkdir(parents=True)
            (workflows / "consumer-compatibility.yml").write_text(
                "name: permanent\n", encoding="utf-8"
            )
            self.assertEqual([], VERIFY.temporary_workflows(root))

    def test_yml_and_yaml_temporary_workflows_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflows = root / ".github" / "workflows"
            workflows.mkdir(parents=True)
            (workflows / "temporary-one.yml").write_text("name: one\n", encoding="utf-8")
            (workflows / "temporary-two.yaml").write_text("name: two\n", encoding="utf-8")
            self.assertEqual(
                [
                    Path(".github/workflows/temporary-one.yml"),
                    Path(".github/workflows/temporary-two.yaml"),
                ],
                VERIFY.temporary_workflows(root),
            )


if __name__ == "__main__":
    unittest.main()
