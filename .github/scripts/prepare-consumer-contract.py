#!/usr/bin/env python3
"""Prepare a consumer checkout to resolve the exact current library reactor.

The script deliberately edits only version text belonging to
io.github.carstenartur:jgit-storage-hibernate-* dependencies. It supports direct,
dependency-managed and property-backed versions without reformatting complete POM files.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

GROUP_ID = "io.github.carstenartur"
ARTIFACT_PREFIX = "jgit-storage-hibernate-"
DEPENDENCY_BLOCK = re.compile(r"<dependency\b[^>]*>.*?</dependency>", re.DOTALL)
GROUP = re.compile(r"<groupId>\s*([^<]+?)\s*</groupId>", re.DOTALL)
ARTIFACT = re.compile(r"<artifactId>\s*([^<]+?)\s*</artifactId>", re.DOTALL)
VERSION = re.compile(r"(<version>\s*)([^<]+?)(\s*</version>)", re.DOTALL)
PROPERTY_REFERENCE = re.compile(r"^\$\{([A-Za-z0-9_.-]+)}$")


@dataclass(frozen=True)
class DependencyUse:
    pom: str
    artifact: str
    declared_version: str
    source: str


@dataclass(frozen=True)
class PropertyChange:
    pom: str
    property_name: str
    previous_value: str


def pom_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("pom.xml")
        if ".git" not in path.parts and "target" not in path.parts
    )


def dependency_uses(path: Path, root: Path) -> list[DependencyUse]:
    text = path.read_text(encoding="utf-8")
    uses: list[DependencyUse] = []
    for block_match in DEPENDENCY_BLOCK.finditer(text):
        block = block_match.group(0)
        group = GROUP.search(block)
        artifact = ARTIFACT.search(block)
        if group is None or artifact is None:
            continue
        group_id = group.group(1).strip()
        artifact_id = artifact.group(1).strip()
        if group_id != GROUP_ID or not artifact_id.startswith(ARTIFACT_PREFIX):
            continue
        version = VERSION.search(block)
        if version is None:
            declared = "(managed)"
            source = "dependency-management"
        else:
            declared = version.group(2).strip()
            property_match = PROPERTY_REFERENCE.fullmatch(declared)
            source = (
                f"property:{property_match.group(1)}"
                if property_match is not None
                else "literal"
            )
        uses.append(
            DependencyUse(
                pom=path.relative_to(root).as_posix(),
                artifact=artifact_id,
                declared_version=declared,
                source=source,
            )
        )
    return uses


def replace_literal_versions(path: Path, target_version: str) -> int:
    text = path.read_text(encoding="utf-8")
    replacements = 0

    def dependency_replacement(match: re.Match[str]) -> str:
        nonlocal replacements
        block = match.group(0)
        group = GROUP.search(block)
        artifact = ARTIFACT.search(block)
        if group is None or artifact is None:
            return block
        if group.group(1).strip() != GROUP_ID:
            return block
        if not artifact.group(1).strip().startswith(ARTIFACT_PREFIX):
            return block
        version = VERSION.search(block)
        if version is None:
            return block
        current = version.group(2).strip()
        if PROPERTY_REFERENCE.fullmatch(current) is not None or current == target_version:
            return block
        replacements += 1
        return (
            block[: version.start(2)]
            + target_version
            + block[version.end(2) :]
        )

    updated = DEPENDENCY_BLOCK.sub(dependency_replacement, text)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
    return replacements


def referenced_properties(uses: Iterable[DependencyUse]) -> set[str]:
    result: set[str] = set()
    for use in uses:
        if use.source.startswith("property:"):
            result.add(use.source.removeprefix("property:"))
    return result


def replace_property(
    poms: Iterable[Path], root: Path, property_name: str, target_version: str
) -> list[PropertyChange]:
    element = re.compile(
        rf"(<{re.escape(property_name)}>\s*)([^<]+?)(\s*</{re.escape(property_name)}>)",
        re.DOTALL,
    )
    changes: list[PropertyChange] = []
    for path in poms:
        text = path.read_text(encoding="utf-8")

        def property_replacement(match: re.Match[str]) -> str:
            previous = match.group(2).strip()
            if previous == target_version:
                return match.group(0)
            changes.append(
                PropertyChange(
                    pom=path.relative_to(root).as_posix(),
                    property_name=property_name,
                    previous_value=previous,
                )
            )
            return match.group(1) + target_version + match.group(3)

        updated = element.sub(property_replacement, text)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
    return changes


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer", required=True)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--report", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = args.root.resolve()
    poms = pom_files(root)
    if not poms:
        raise SystemExit(f"No pom.xml found below {root}")

    uses = [use for path in poms for use in dependency_uses(path, root)]
    if not uses:
        raise SystemExit(
            f"{args.consumer} declares no {GROUP_ID}:{ARTIFACT_PREFIX}* dependency"
        )

    literal_replacements = sum(
        replace_literal_versions(path, args.version) for path in poms
    )
    property_changes: list[PropertyChange] = []
    for property_name in sorted(referenced_properties(uses)):
        changes = replace_property(poms, root, property_name, args.version)
        if not changes:
            raise SystemExit(
                f"Referenced property {property_name!r} was not found in any consumer POM"
            )
        property_changes.extend(changes)

    # A managed dependency is acceptable only when another matching declaration supplies a
    # literal or property-backed version. Maven's dependency tree is the final resolution proof.
    managed_artifacts = {use.artifact for use in uses if use.declared_version == "(managed)"}
    versioned_artifacts = {
        use.artifact for use in uses if use.declared_version != "(managed)"
    }
    unresolved = sorted(managed_artifacts - versioned_artifacts)

    report = {
        "consumer": args.consumer,
        "targetVersion": args.version,
        "modules": sorted({use.artifact for use in uses}),
        "dependencyUses": [asdict(use) for use in uses],
        "literalReplacements": literal_replacements,
        "propertyChanges": [asdict(change) for change in property_changes],
        "managedWithoutLocalVersion": unresolved,
        "changedPoms": sorted(
            {
                change.pom for change in property_changes
            }
            | {
                use.pom
                for use in uses
                if use.source == "literal" and use.declared_version != args.version
            }
        ),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(
        f"Prepared {args.consumer}: modules={','.join(report['modules'])}; "
        f"literal replacements={literal_replacements}; "
        f"property replacements={len(property_changes)}"
    )
    if unresolved:
        print(
            "Managed dependencies without a local matching version declaration: "
            + ", ".join(unresolved)
        )


if __name__ == "__main__":
    main()
