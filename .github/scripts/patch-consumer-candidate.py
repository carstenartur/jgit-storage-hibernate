#!/usr/bin/env python3
"""Patch only jgit-storage-hibernate dependency versions in a downstream Maven checkout."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

GROUP = "io.github.carstenartur"
ARTIFACT_PREFIX = "jgit-storage-hibernate-"
DEPENDENCY = re.compile(r"<dependency>(?P<body>.*?)</dependency>", re.DOTALL)
GROUP_ID = re.compile(r"<groupId>\s*" + re.escape(GROUP) + r"\s*</groupId>")
ARTIFACT_ID = re.compile(
    r"<artifactId>\s*(?P<artifact>" + re.escape(ARTIFACT_PREFIX) + r"[A-Za-z0-9_.-]+)\s*</artifactId>"
)
VERSION = re.compile(r"<version>\s*(?P<version>[^<]+?)\s*</version>")
PROPERTY_REFERENCE = re.compile(r"^\$\{(?P<name>[A-Za-z0-9_.-]+)\}$")


class PatchError(RuntimeError):
    pass


def _replace_property(files: list[Path], name: str, candidate: str) -> tuple[str, list[str]]:
    pattern = re.compile(
        rf"(<{re.escape(name)}>\s*)(?P<value>[^<]+?)(\s*</{re.escape(name)}>)"
    )
    matches: list[tuple[Path, str]] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        for match in pattern.finditer(text):
            matches.append((path, match.group("value").strip()))
    if not matches:
        raise PatchError(f"Could not resolve Maven property {name!r}")
    original_values = {value for _, value in matches}
    if len(original_values) != 1:
        raise PatchError(f"Maven property {name!r} has conflicting values: {sorted(original_values)}")
    changed: list[str] = []
    for path, _ in matches:
        text = path.read_text(encoding="utf-8")
        updated, count = pattern.subn(rf"\g<1>{candidate}\g<3>", text)
        if count:
            path.write_text(updated, encoding="utf-8")
            changed.append(str(path))
    return next(iter(original_values)), changed


def patch(root: Path, candidate: str) -> dict[str, object]:
    poms = sorted(root.rglob("pom.xml"))
    if not poms:
        raise PatchError(f"No pom.xml files found under {root}")

    modules: set[str] = set()
    literal_changes: list[dict[str, str]] = []
    properties: dict[str, set[str]] = {}
    changed_files: set[str] = set()

    for pom in poms:
        text = pom.read_text(encoding="utf-8")

        def replace_dependency(match: re.Match[str]) -> str:
            block = match.group(0)
            body = match.group("body")
            if not GROUP_ID.search(body):
                return block
            artifact_match = ARTIFACT_ID.search(body)
            if artifact_match is None:
                return block
            artifact = artifact_match.group("artifact")
            modules.add(artifact)
            version_match = VERSION.search(body)
            if version_match is None:
                return block
            version = version_match.group("version").strip()
            property_match = PROPERTY_REFERENCE.fullmatch(version)
            if property_match is not None:
                properties.setdefault(property_match.group("name"), set()).add(artifact)
                return block
            if version == candidate:
                return block
            replaced = VERSION.sub(f"<version>{candidate}</version>", block, count=1)
            literal_changes.append(
                {
                    "file": str(pom.relative_to(root)),
                    "artifact": artifact,
                    "from": version,
                    "to": candidate,
                }
            )
            changed_files.add(str(pom.relative_to(root)))
            return replaced

        updated = DEPENDENCY.sub(replace_dependency, text)
        if updated != text:
            pom.write_text(updated, encoding="utf-8")

    if not modules:
        raise PatchError("Consumer checkout declares no jgit-storage-hibernate dependencies")

    property_changes: list[dict[str, object]] = []
    for name, artifacts in sorted(properties.items()):
        original, files = _replace_property(poms, name, candidate)
        relative_files = sorted(str(Path(path).relative_to(root)) for path in files)
        changed_files.update(relative_files)
        property_changes.append(
            {
                "property": name,
                "artifacts": sorted(artifacts),
                "from": original,
                "to": candidate,
                "files": relative_files,
            }
        )

    if not literal_changes and not property_changes:
        raise PatchError(
            f"Found modules {sorted(modules)} but no version location that can select candidate {candidate}"
        )

    return {
        "schemaVersion": 1,
        "candidateVersion": candidate,
        "modules": sorted(modules),
        "changedFiles": sorted(changed_files),
        "literalChanges": literal_changes,
        "propertyChanges": property_changes,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = patch(args.root.resolve(), args.version.strip())
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
