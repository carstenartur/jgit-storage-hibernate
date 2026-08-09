#!/usr/bin/env python3
"""Validate the pinned real-consumer compatibility descriptor."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / ".github" / "consumer-compatibility.json"
EXPECTED = {"audio-analyzer", "taxonomy", "sandbox"}
SHA = re.compile(r"^[0-9a-f]{40}$")
PREFIX = "jgit-storage-hibernate-"


def fail(message: str) -> None:
    raise SystemExit("Consumer compatibility contract error: " + message)


def text(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{field} must be a non-empty string")
    return value.strip()


def matrix() -> dict[str, object]:
    data = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 2:
        fail("schemaVersion must be 2")
    if data.get("candidateSource") != "same-run isolated Maven install":
        fail("candidateSource must identify the same-run isolated Maven install")
    consumers = data.get("consumers")
    if not isinstance(consumers, list) or len(consumers) != 3:
        fail("exactly three consumers must be declared")
    ids: set[str] = set()
    repositories: set[str] = set()
    normalized: list[dict[str, object]] = []
    for index, raw in enumerate(consumers):
        if not isinstance(raw, dict):
            fail(f"consumers[{index}] must be an object")
        consumer_id = text(raw.get("id"), f"consumers[{index}].id")
        repository = text(raw.get("repository"), f"{consumer_id}.repository")
        ref = text(raw.get("ref"), f"{consumer_id}.ref")
        if SHA.fullmatch(ref) is None:
            fail(f"{consumer_id}.ref must be an immutable 40-character SHA")
        branch = text(raw.get("defaultBranch"), f"{consumer_id}.defaultBranch")
        script = text(raw.get("contractScript"), f"{consumer_id}.contractScript")
        modules = raw.get("expectedModules")
        if not isinstance(modules, list) or not modules:
            fail(f"{consumer_id}.expectedModules must be a non-empty list")
        normalized_modules: list[str] = []
        for module in modules:
            value = text(module, f"{consumer_id}.expectedModules")
            if not value.startswith(PREFIX) or value == PREFIX + "benchmarks":
                fail(f"{consumer_id} has invalid runtime module {value!r}")
            normalized_modules.append(value)
        if consumer_id in ids or repository in repositories:
            fail(f"duplicate consumer id/repository for {consumer_id}")
        ids.add(consumer_id)
        repositories.add(repository)
        normalized.append(
            {
                "id": consumer_id,
                "repository": repository,
                "ref": ref,
                "defaultBranch": branch,
                "contractScript": script,
                "expectedModules": sorted(set(normalized_modules)),
            }
        )
    if ids != EXPECTED:
        fail(f"consumer ids must be exactly {sorted(EXPECTED)}")
    return {"include": normalized}


def main() -> None:
    payload = matrix()
    print(json.dumps(payload, separators=(",", ":")))


if __name__ == "__main__":
    main()
