#!/usr/bin/env python3
"""Verify exact jgit-storage-hibernate resolution in a Maven dependency tree."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

GROUP_ID = "io.github.carstenartur"
ARTIFACT_PREFIX = "jgit-storage-hibernate-"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
COORDINATE = re.compile(
    rf"{re.escape(GROUP_ID)}:({re.escape(ARTIFACT_PREFIX)}[A-Za-z0-9_.-]+):"
    r"([A-Za-z0-9_.-]+):"
    r"(?:([A-Za-z0-9_.-]+):)?"
    r"([^:\s()]+):"
    r"([A-Za-z0-9_.-]+)"
)


def coordinates(text: str) -> list[dict[str, str]]:
    clean = ANSI.sub("", text)
    found: list[dict[str, str]] = []
    for match in COORDINATE.finditer(clean):
        artifact, packaging, classifier, version, scope = match.groups()
        found.append(
            {
                "artifact": artifact,
                "packaging": packaging,
                "classifier": classifier or "",
                "version": version,
                "scope": scope,
            }
        )
    unique: dict[tuple[str, str, str, str, str], dict[str, str]] = {}
    for item in found:
        key = (
            item["artifact"],
            item["packaging"],
            item["classifier"],
            item["version"],
            item["scope"],
        )
        unique[key] = item
    return sorted(
        unique.values(),
        key=lambda item: (
            item["artifact"],
            item["version"],
            item["scope"],
            item["classifier"],
        ),
    )


def verify(
    resolved: list[dict[str, str]], expected_version: str, forbidden: set[str]
) -> None:
    if not resolved:
        raise ValueError("No jgit-storage-hibernate dependency was resolved")
    wrong = [item for item in resolved if item["version"] != expected_version]
    if wrong:
        details = ", ".join(
            f"{item['artifact']}:{item['version']}" for item in wrong
        )
        raise ValueError(
            f"Expected every library module at {expected_version}, found {details}"
        )
    present_forbidden = sorted(
        {item["artifact"] for item in resolved if item["artifact"] in forbidden}
    )
    if present_forbidden:
        raise ValueError(
            "Forbidden consumer dependency artifact(s): "
            + ", ".join(present_forbidden)
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tree", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--forbid", action="append", default=[])
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    resolved = coordinates(args.tree.read_text(encoding="utf-8", errors="replace"))
    verify(resolved, args.version, set(args.forbid))
    report = {
        "expectedVersion": args.version,
        "resolvedModules": resolved,
        "forbiddenArtifacts": sorted(set(args.forbid)),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    modules = ", ".join(
        f"{item['artifact']}:{item['version']}" for item in resolved
    )
    print(f"Verified exact consumer resolution: {modules}")


if __name__ == "__main__":
    main()
