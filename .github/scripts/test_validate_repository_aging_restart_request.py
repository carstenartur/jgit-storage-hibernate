#!/usr/bin/env python3
"""Tests for reviewed repository-aging restart-evidence requests."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).parents[2]
VALIDATOR_PATH = (
    ROOT / ".github/scripts/validate_repository_aging_restart_request.py"
)


def _load_validator() -> ModuleType:
    specification = importlib.util.spec_from_file_location(
        "validate_repository_aging_restart_request", VALIDATOR_PATH
    )
    if specification is None or specification.loader is None:
        raise RuntimeError(f"Cannot load {VALIDATOR_PATH}")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


VALIDATOR = _load_validator()
SOURCE = "a" * 40


class RestartEvidenceRequestTest(unittest.TestCase):

    def test_exact_current_main_request_is_normalized(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            _write_request(path)

            request = VALIDATOR.validate_request(path, SOURCE)

            self.assertEqual(1, request["schemaVersion"])
            self.assertTrue(request["enabled"])
            self.assertEqual("initial-restart-repeat-evidence", request["requestId"])
            self.assertEqual(SOURCE, request["sourceCommit"])

    def test_stale_main_commit_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            _write_request(path)

            with self.assertRaisesRegex(VALIDATOR.RequestError, "stale"):
                VALIDATOR.validate_request(path, "b" * 40)

    def test_disabled_and_extra_key_requests_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            request = _request()
            request["enabled"] = False
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "enabled"):
                VALIDATOR.validate_request(path, SOURCE)

            request = _request()
            request["unexpected"] = True
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "keys"):
                VALIDATOR.validate_request(path, SOURCE)

    def test_request_id_and_reason_are_single_line_and_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            request = _request()
            request["requestId"] = "../invalid"
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "requestId"):
                VALIDATOR.validate_request(path, SOURCE)

            request = _request()
            request["reason"] = "first line\nsecond line"
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "one line"):
                VALIDATOR.validate_request(path, SOURCE)


def _request() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "enabled": True,
        "requestId": "initial-restart-repeat-evidence",
        "sourceCommit": SOURCE,
        "reason": "Measure cold and warm restart evidence with three repeats.",
    }


def _write_request(path: Path) -> None:
    path.write_text(json.dumps(_request(), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
