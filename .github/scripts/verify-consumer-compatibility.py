#!/usr/bin/env python3
"""Validate the pinned, consumer-specific downstream compatibility contract."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / ".github" / "consumer-compatibility.json"
WORKFLOW = ROOT / ".github" / "workflows" / "consumer-compatibility.yml"
EXPECTED_IDS = {"audio-analyzer", "taxonomy", "sandbox"}
SHA = re.compile(r"^[0-9a-f]{40}$")


def fail(message: str) -> None:
    raise SystemExit(f"Consumer compatibility contract error: {message}")


def require_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{name} must be a non-empty string")
    return value.strip()


def main() -> None:
    data = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        fail("schemaVersion must be 1")

    policy = data.get("policy")
    if not isinstance(policy, dict):
        fail("policy must be an object")
    if policy.get("remoteSnapshots") is not False:
        fail("remoteSnapshots must remain false")
    if "local Maven install" not in require_text(
        policy.get("candidateSource"), "policy.candidateSource"
    ):
        fail("candidate artifacts must come from a same-job local Maven install")

    consumers = data.get("consumers")
    if not isinstance(consumers, list) or len(consumers) != 3:
        fail("exactly three consumers must be declared")

    by_id: dict[str, dict[str, Any]] = {}
    repositories: set[str] = set()
    for index, raw in enumerate(consumers):
        if not isinstance(raw, dict):
            fail(f"consumers[{index}] must be an object")
        consumer_id = require_text(raw.get("id"), f"consumers[{index}].id")
        if consumer_id in by_id:
            fail(f"duplicate consumer id {consumer_id!r}")
        repository = require_text(
            raw.get("repository"), f"consumers[{index}].repository"
        )
        if repository in repositories:
            fail(f"duplicate consumer repository {repository!r}")
        ref = require_text(raw.get("ref"), f"consumers[{index}].ref")
        if SHA.fullmatch(ref) is None:
            fail(f"{consumer_id} ref must be an immutable 40-character commit SHA")
        modules = raw.get("upstreamModules")
        if not isinstance(modules, list) or not modules:
            fail(f"{consumer_id} must declare at least one upstream module")
        contract = raw.get("contract")
        if not isinstance(contract, list) or len(contract) < 3:
            fail(f"{consumer_id} must declare a meaningful behavioral contract")
        require_text(raw.get("smokeCommand"), f"{consumer_id}.smokeCommand")
        by_id[consumer_id] = raw
        repositories.add(repository)

    if set(by_id) != EXPECTED_IDS:
        fail(f"consumer ids must be exactly {sorted(EXPECTED_IDS)}")

    audio_modules = set(by_id["audio-analyzer"]["upstreamModules"])
    if "jgit-storage-hibernate-search" not in audio_modules:
        fail("audio-analyzer must validate the Search module")
    for consumer_id in ("taxonomy", "sandbox"):
        if "jgit-storage-hibernate-search" in set(
            by_id[consumer_id]["upstreamModules"]
        ):
            fail(
                f"{consumer_id} must not be treated as an upstream Search consumer yet"
            )

    workflow = WORKFLOW.read_text(encoding="utf-8")
    for consumer_id, consumer in by_id.items():
        repository = str(consumer["repository"])
        ref = str(consumer["ref"])
        if repository not in workflow:
            fail(f"workflow does not check out {consumer_id} repository {repository}")
        if ref not in workflow:
            fail(f"workflow does not pin {consumer_id} to {ref}")

    for required in (
        "name: Audio Analyzer — Core and Search workflow history",
        "name: Taxonomy — Core schema and migration contract",
        "name: Sandbox — Core lifecycle, adoption and packaging",
        "-Djgit-storage-hibernate.version=\"$CANDIDATE_VERSION\"",
        "mvn -B -f storage/pom.xml",
        "if-no-files-found: error",
    ):
        if required not in workflow:
            fail(f"workflow is missing required contract fragment {required!r}")

    floating_refs = re.findall(
        r"repository:\s*carstenartur/(?:audio-analyzer|Taxonomy|sandbox)\s*\n"
        r"\s*ref:\s*(?![0-9a-f]{40}\s*$)([^\n]+)",
        workflow,
        flags=re.MULTILINE,
    )
    if floating_refs:
        fail(f"consumer checkout uses floating refs: {floating_refs}")

    print(
        "Consumer compatibility contract verified: "
        "audio-analyzer=Core+Search, Taxonomy=Core schema, "
        "Sandbox=Core lifecycle/adoption/packaging"
    )


if __name__ == "__main__":
    main()
