#!/usr/bin/env python3
"""Summarize retained real-consumer compatibility artifacts for one workflow run."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def _load_json(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def _read_text(path: Path) -> str | None:
    if not path.is_file():
        return None
    value = path.read_text(encoding="utf-8").strip()
    return value or None


def _contract_consumers(descriptor: Path) -> list[dict[str, Any]]:
    data = _load_json(descriptor)
    if data is None or data.get("schemaVersion") != 2:
        raise ValueError(f"{descriptor} must be a schemaVersion 2 consumer descriptor")
    consumers = data.get("consumers")
    if not isinstance(consumers, list) or not consumers:
        raise ValueError(f"{descriptor} has no consumers")
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(consumers):
        if not isinstance(raw, dict):
            raise ValueError(f"consumers[{index}] must be an object")
        consumer_id = str(raw.get("id", "")).strip()
        if not consumer_id:
            raise ValueError(f"consumers[{index}].id must be non-empty")
        normalized.append(
            {
                "id": consumer_id,
                "displayName": str(raw.get("displayName", consumer_id)).strip() or consumer_id,
                "repository": str(raw.get("repository", "")).strip(),
                "ref": str(raw.get("ref", "")).strip(),
                "defaultBranch": str(raw.get("defaultBranch", "")).strip(),
                "contractScript": str(raw.get("contractScript", "")).strip(),
                "expectedModules": sorted(str(value) for value in raw.get("expectedModules", [])),
            }
        )
    return normalized


def _versions(substitution: dict[str, Any] | None) -> tuple[str, str]:
    if substitution is None:
        return "—", "—"
    originals: set[str] = set()
    for item in substitution.get("literalChanges", []):
        if isinstance(item, dict) and item.get("from"):
            originals.add(str(item["from"]))
    for item in substitution.get("propertyChanges", []):
        if isinstance(item, dict) and item.get("from"):
            originals.add(str(item["from"]))
    original = ", ".join(sorted(originals)) or "already selected"
    candidate = str(substitution.get("candidateVersion", "")).strip() or "—"
    return original, candidate


def _artifact_dir(root: Path, mode: str, consumer_id: str) -> Path | None:
    names = [f"consumer-{mode}-{consumer_id}", consumer_id]
    for name in names:
        direct = root / name
        if direct.is_dir():
            return direct
    matches = sorted(root.glob(f"**/consumer-{mode}-{consumer_id}"))
    return matches[0] if matches else None


def _locate(base: Path | None, candidates: list[str]) -> Path | None:
    if base is None:
        return None
    for relative in candidates:
        path = base / relative
        if path.is_file():
            return path
    for name in candidates:
        matches = sorted(base.glob(f"**/{Path(name).name}"))
        if matches:
            return matches[0]
    return None


def _mode_evidence(root: Path, mode: str, consumer_id: str) -> dict[str, Any]:
    base = _artifact_dir(root, mode, consumer_id)
    result_path = _locate(base, ["result.json", "target/jgit-storage-hibernate-contract/result.json"])
    metadata_path = _locate(base, ["run-metadata.json"])
    commit_path = _locate(base, ["consumer-commit.txt"])
    substitution_path = _locate(base, ["substitution.json"])
    result = _load_json(result_path) if result_path else None
    metadata = _load_json(metadata_path) if metadata_path else None
    substitution = _load_json(substitution_path) if substitution_path else None
    commit = _read_text(commit_path) if commit_path else None
    return {
        "available": base is not None,
        "passed": result is not None,
        "result": result,
        "metadata": metadata,
        "substitution": substitution,
        "commit": commit,
    }


def _status(evidence: dict[str, Any]) -> str:
    if not evidence["available"]:
        return "not run"
    return "passed" if evidence["passed"] else "failed/incomplete"


def _duration(evidence: dict[str, Any]) -> str:
    metadata = evidence.get("metadata")
    if not isinstance(metadata, dict):
        return "—"
    value = metadata.get("durationSeconds")
    try:
        seconds = int(value)
    except (TypeError, ValueError):
        return "—"
    return f"{seconds // 60}m {seconds % 60}s" if seconds >= 60 else f"{seconds}s"


def _markdown(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def _contract(candidate: dict[str, Any], baseline: dict[str, Any], fallback: str) -> str:
    for evidence in (candidate, baseline):
        result = evidence.get("result")
        if isinstance(result, dict):
            value = str(result.get("contract", "")).strip()
            if value:
                return value
    return fallback


def build_summary(descriptor: Path, artifacts: Path, run_url: str) -> str:
    lines = [
        "### Real-consumer compatibility",
        "",
        "Candidate artifacts come from the exact library checkout under test. Scheduled baselines follow each declared default branch; pull-request and push baselines use the immutable pinned commit.",
        "",
        "| Consumer | Source commit | Modules | Original → candidate | Candidate | Baseline | Contract |",
        "|---|---|---|---|---:|---:|---|",
    ]
    for consumer in _contract_consumers(descriptor):
        candidate = _mode_evidence(artifacts, "candidate", consumer["id"])
        baseline = _mode_evidence(artifacts, "baseline", consumer["id"])
        source_commit = candidate.get("commit") or baseline.get("commit") or consumer["ref"]
        source = source_commit[:7] if source_commit else "—"
        modules = ", ".join(f"`{value}`" for value in consumer["expectedModules"]) or "—"
        original, substituted = _versions(candidate.get("substitution"))
        candidate_status = f"{_status(candidate)} ({_duration(candidate)})"
        baseline_status = f"{_status(baseline)} ({_duration(baseline)})"
        contract = _contract(candidate, baseline, consumer["contractScript"])
        lines.append(
            f"| {_markdown(consumer['displayName'])} | `{source}` | {modules} | "
            f"`{_markdown(original)}` → `{_markdown(substituted)}` | "
            f"{candidate_status} | {baseline_status} | {_markdown(contract)} |"
        )
    if run_url:
        lines.extend(["", f"[Open retained compatibility artifacts and job logs]({run_url})"])
    lines.extend(
        [
            "",
            "The module list reports the pinned Maven contract. It is compatibility evidence, not a claim that these library microbenchmarks were measured inside the consuming application.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--descriptor", type=Path, default=Path(".github/consumer-compatibility.json"))
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--run-url", default="")
    args = parser.parse_args()
    try:
        summary = build_summary(args.descriptor, args.artifacts, args.run_url)
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        raise SystemExit("Consumer compatibility summary error: " + str(failure)) from failure
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(summary, encoding="utf-8")
    print(summary)


if __name__ == "__main__":
    main()
