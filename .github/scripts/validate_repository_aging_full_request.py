#!/usr/bin/env python3
"""Validate a reviewed request to dispatch the full repository-aging matrix."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REQUEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
ALLOWED_KEYS = {
    "schemaVersion",
    "enabled",
    "requestId",
    "sourceCommit",
    "reason",
}


class RequestError(ValueError):
    """The reviewed full-aging request is invalid or stale."""


def _reject_non_finite(value: str) -> None:
    raise RequestError(f"Non-finite JSON constant {value!r}")


def validate_request(path: Path, expected_source_commit: str) -> dict[str, Any]:
    """Return a normalized request only when it targets exact current main."""
    if not COMMIT.fullmatch(expected_source_commit):
        raise RequestError(
            "Expected source commit must be a lowercase 40-character SHA: "
            f"{expected_source_commit!r}"
        )
    try:
        request = json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=_reject_non_finite,
        )
    except OSError as failure:
        raise RequestError(f"Cannot read full-aging request {path}") from failure
    except json.JSONDecodeError as failure:
        raise RequestError(f"Full-aging request is not strict JSON: {path}") from failure
    if not isinstance(request, dict):
        raise RequestError("Full-aging request must be a JSON object")
    keys = set(request)
    if keys != ALLOWED_KEYS:
        raise RequestError(
            "Full-aging request keys must be exactly "
            f"{sorted(ALLOWED_KEYS)}, found {sorted(keys)}"
        )
    if request["schemaVersion"] != 1:
        raise RequestError("Full-aging request schemaVersion must be 1")
    if request["enabled"] is not True:
        raise RequestError("Full-aging request must set enabled to true")

    request_id = request["requestId"]
    if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
        raise RequestError(
            "Full-aging requestId must contain 1-64 letters, digits, dot, "
            "underscore or hyphen and start with a letter or digit"
        )
    source_commit = request["sourceCommit"]
    if not isinstance(source_commit, str) or not COMMIT.fullmatch(source_commit):
        raise RequestError(
            "Full-aging sourceCommit must be a lowercase 40-character SHA"
        )
    if source_commit != expected_source_commit:
        raise RequestError(
            "Full-aging request is stale: requested sourceCommit "
            f"{source_commit}, current main is {expected_source_commit}"
        )
    reason = request["reason"]
    if not isinstance(reason, str) or not 10 <= len(reason) <= 300:
        raise RequestError("Full-aging reason must contain 10-300 characters")
    if "\n" in reason or "\r" in reason:
        raise RequestError("Full-aging reason must be one line")

    return {
        "schemaVersion": 1,
        "enabled": True,
        "requestId": request_id,
        "sourceCommit": source_commit,
        "reason": reason,
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True, type=Path)
    parser.add_argument("--expected-source-commit", required=True)
    parser.add_argument("--github-output", type=Path)
    return parser


def main(argv: list[str] | None = None) -> None:
    arguments = _parser().parse_args(argv)
    request = validate_request(
        arguments.request,
        arguments.expected_source_commit,
    )
    if arguments.github_output is not None:
        with arguments.github_output.open("a", encoding="utf-8") as output:
            output.write(f"request_id={request['requestId']}\n")
            output.write(f"source_commit={request['sourceCommit']}\n")
    print(json.dumps(request, sort_keys=True))


if __name__ == "__main__":
    main()
