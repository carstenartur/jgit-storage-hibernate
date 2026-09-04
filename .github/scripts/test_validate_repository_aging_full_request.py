#!/usr/bin/env python3
"""Tests for reviewed full repository-aging evidence requests."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).parents[2]
VALIDATOR_PATH = ROOT / ".github/scripts/validate_repository_aging_full_request.py"


def _load_validator() -> ModuleType:
    specification = importlib.util.spec_from_file_location(
        "validate_repository_aging_full_request", VALIDATOR_PATH
    )
    if specification is None or specification.loader is None:
        raise RuntimeError(f"Cannot load {VALIDATOR_PATH}")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


VALIDATOR = _load_validator()
SOURCE = "a" * 40


class FullAgingEvidenceRequestTest(unittest.TestCase):

    def test_exact_current_main_request_is_normalized(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            _write_request(path)

            request = VALIDATOR.validate_request(path, SOURCE)

            self.assertEqual(1, request["schemaVersion"])
            self.assertTrue(request["enabled"])
            self.assertEqual("initial-full-aging-evidence", request["requestId"])
            self.assertEqual(SOURCE, request["sourceCommit"])

    def test_github_outputs_use_expression_safe_names(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            path = root / "request.json"
            github_output = root / "github-output"
            _write_request(path)

            with contextlib.redirect_stdout(io.StringIO()):
                VALIDATOR.main(
                    [
                        "--request",
                        str(path),
                        "--expected-source-commit",
                        SOURCE,
                        "--github-output",
                        str(github_output),
                    ]
                )

            self.assertEqual(
                [
                    "request_id=initial-full-aging-evidence",
                    f"source_commit={SOURCE}",
                ],
                github_output.read_text(encoding="utf-8").splitlines(),
            )

    def test_stale_main_commit_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            _write_request(path)

            with self.assertRaisesRegex(VALIDATOR.RequestError, "stale"):
                VALIDATOR.validate_request(path, "b" * 40)

    def test_invalid_expected_and_requested_commits_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            _write_request(path)
            with self.assertRaisesRegex(VALIDATOR.RequestError, "Expected source"):
                VALIDATOR.validate_request(path, "main")

            request = _request()
            request["sourceCommit"] = "A" * 40
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "sourceCommit"):
                VALIDATOR.validate_request(path, SOURCE)

    def test_disabled_extra_key_and_non_finite_requests_are_rejected(self) -> None:
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

            non_finite_request = (
                '{"schemaVersion": NaN, "enabled": true, '
                '"requestId": "initial-full-aging-evidence", '
                f'"sourceCommit": "{SOURCE}", "reason": "Run full evidence."'
                "}"
            )
            path.write_text(non_finite_request, encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "Non-finite"):
                VALIDATOR.validate_request(path, SOURCE)

    def test_boolean_schema_and_duplicate_keys_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            request = _request()
            request["schemaVersion"] = True
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "integer 1"):
                VALIDATOR.validate_request(path, SOURCE)

            path.write_text(
                '{"schemaVersion": 1, "enabled": true, '
                '"requestId": "first", "requestId": "second", '
                f'"sourceCommit": "{SOURCE}", '
                '"reason": "Run complete full aging evidence."}',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VALIDATOR.RequestError, "Duplicate JSON key"):
                VALIDATOR.validate_request(path, SOURCE)

    def test_request_id_and_reason_are_single_line_and_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "request.json"
            request = _request()
            request["requestId"] = "../invalid"
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "requestId"):
                VALIDATOR.validate_request(path, SOURCE)

            for reason in ("first line\nsecond line", "tab\tcharacter"):
                request = _request()
                request["reason"] = reason
                path.write_text(json.dumps(request), encoding="utf-8")
                with self.subTest(reason=reason):
                    with self.assertRaisesRegex(
                        VALIDATOR.RequestError, "control characters"
                    ):
                        VALIDATOR.validate_request(path, SOURCE)

            request = _request()
            request["reason"] = "short"
            path.write_text(json.dumps(request), encoding="utf-8")
            with self.assertRaisesRegex(VALIDATOR.RequestError, "10-300"):
                VALIDATOR.validate_request(path, SOURCE)


def _request() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "enabled": True,
        "requestId": "initial-full-aging-evidence",
        "sourceCommit": SOURCE,
        "reason": "Measure the complete sharded repository-aging matrix.",
    }


def _write_request(path: Path) -> None:
    path.write_text(json.dumps(_request(), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
