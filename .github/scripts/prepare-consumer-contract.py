#!/usr/bin/env python3
"""Prepare a consumer checkout to resolve the exact current library reactor.

Only io.github.carstenartur:jgit-storage-hibernate-* dependency versions are
changed. Literal, dependency-managed and property-backed declarations are
supported without reformatting complete POM files.

Property substitution is fail-closed:
* project identity placeholders such as revision/sha1/changelist are forbidden;
* a property referenced by unrelated POM content is forbidden;
* one inherited/global definition is allowed;
* multiple definitions are allowed only when every referencing POM owns exactly
  one local definition (the Sandbox multi-module layout).
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
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
DEPENDENCY_BLOCK = re.compile(r"<dependency\b[^>]*>.*?</dependency>", re.DOTALL)
GROUP = re.compile(r"<groupId>\s*([^<]+?)\s*</groupId>", re.DOTALL)
ARTIFACT = re.compile(r"<artifactId>\s*([^<]+?)\s*</artifactId>", re.DOTALL)
VERSION = re.compile(r"(<version>\s*)([^<]+?)(\s*</version>)", re.DOTALL)
PROPERTY_REFERENCE = re.compile(r"^\$\{([A-Za-z0-9_.-]+)}$")
RESERVED_PROJECT_VERSION_PROPERTIES = {"revision", "sha1", "changelist"}


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


@dataclass(frozen=True)
class PropertySafety:
    property_name: str
    target_references: int
    total_references: int
    definitions: int
    definition_poms: tuple[str, ...]
    mode: str


def pom_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("pom.xml")
        if ".git" not in path.parts and "target" not in path.parts
    )


def uncommented(text: str) -> str:
    return COMMENT.sub("", text)


def non_comment_segments(text: str) -> list[str]:
    return re.split(r"(<!--.*?-->)", text, flags=re.DOTALL)


def dependency_uses(path: Path, root: Path) -> list[DependencyUse]:
    text = uncommented(path.read_text(encoding="utf-8"))
    uses: list[DependencyUse] = []
    for block in DEPENDENCY_BLOCK.findall(text):
        group = GROUP.search(block)
        artifact = ARTIFACT.search(block)
        if group is None or artifact is None:
            continue
        artifact_id = artifact.group(1).strip()
        if (
            group.group(1).strip() != GROUP_ID
            or not artifact_id.startswith(ARTIFACT_PREFIX)
        ):
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
        return block[: version.start(2)] + target_version + block[version.end(2) :]

    parts = non_comment_segments(text)
    for index in range(0, len(parts), 2):
        parts[index] = DEPENDENCY_BLOCK.sub(dependency_replacement, parts[index])
    updated = "".join(parts)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
    return replacements


def referenced_properties(uses: Iterable[DependencyUse]) -> set[str]:
    return {
        use.source.removeprefix("property:")
        for use in uses
        if use.source.startswith("property:")
    }


def property_definition_pattern(property_name: str) -> re.Pattern[str]:
    return re.compile(
        rf"(<{re.escape(property_name)}>\s*)([^<]+?)(\s*</{re.escape(property_name)}>)",
        re.DOTALL,
    )


def validate_property_substitution(
    poms: Iterable[Path],
    uses: Iterable[DependencyUse],
    root: Path,
    property_name: str,
) -> PropertySafety:
    pom_list = list(poms)
    use_list = list(uses)
    if property_name in RESERVED_PROJECT_VERSION_PROPERTIES:
        raise ValueError(
            f"Refusing to replace reserved project identity property {property_name!r}"
        )

    reference = "${" + property_name + "}"
    target_by_pom: dict[str, int] = {}
    for use in use_list:
        if use.source == f"property:{property_name}":
            target_by_pom[use.pom] = target_by_pom.get(use.pom, 0) + 1

    total_by_pom: dict[str, int] = {}
    definitions_by_pom: dict[str, int] = {}
    definition_pattern = property_definition_pattern(property_name)
    for path in pom_list:
        relative = path.relative_to(root).as_posix()
        text = uncommented(path.read_text(encoding="utf-8"))
        references = text.count(reference)
        definitions = len(definition_pattern.findall(text))
        if references:
            total_by_pom[relative] = references
        if definitions:
            definitions_by_pom[relative] = definitions

    target_references = sum(target_by_pom.values())
    total_references = sum(total_by_pom.values())
    definitions = sum(definitions_by_pom.values())

    if total_references != target_references:
        raise ValueError(
            f"Property {property_name!r} is shared outside "
            f"{GROUP_ID}:{ARTIFACT_PREFIX}* dependency versions: "
            f"target references={target_references}, "
            f"all POM references={total_references}"
        )
    if definitions == 0:
        raise ValueError(f"Property {property_name!r} has no definition")

    definition_poms = tuple(sorted(definitions_by_pom))
    if definitions == 1:
        mode = "single-definition"
    else:
        duplicate_poms = sorted(
            pom for pom, count in definitions_by_pom.items() if count != 1
        )
        if duplicate_poms:
            raise ValueError(
                f"Property {property_name!r} has multiple definitions in one POM: "
                + ", ".join(duplicate_poms)
            )
        target_poms = set(target_by_pom)
        defined_poms = set(definitions_by_pom)
        if target_poms != defined_poms:
            raise ValueError(
                f"Property {property_name!r} has multiple definitions, but they are "
                "safe only when every referencing POM owns exactly one local definition; "
                f"referencing POMs={sorted(target_poms)}, "
                f"definition POMs={sorted(defined_poms)}"
            )
        mode = "module-local-definitions"

    return PropertySafety(
        property_name=property_name,
        target_references=target_references,
        total_references=total_references,
        definitions=definitions,
        definition_poms=definition_poms,
        mode=mode,
    )


def replace_property(
    poms: Iterable[Path], root: Path, property_name: str, target_version: str
) -> list[PropertyChange]:
    element = property_definition_pattern(property_name)
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

        parts = non_comment_segments(text)
        for index in range(0, len(parts), 2):
            parts[index] = element.sub(property_replacement, parts[index])
        updated = "".join(parts)
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

    property_safety: list[PropertySafety] = []
    for property_name in sorted(referenced_properties(uses)):
        try:
            safety = validate_property_substitution(
                poms, uses, root, property_name
            )
        except ValueError as exception:
            raise SystemExit(str(exception)) from exception
        property_safety.append(safety)

    literal_replacements = sum(
        replace_literal_versions(path, args.version) for path in poms
    )
    property_changes: list[PropertyChange] = []
    for property_name in sorted(referenced_properties(uses)):
        property_changes.extend(
            replace_property(poms, root, property_name, args.version)
        )

    if literal_replacements == 0 and not property_changes:
        raise SystemExit(
            "No consumer dependency version changed; the candidate version may already "
            "be active or all matching dependencies may be unresolved management entries"
        )

    managed_artifacts = {
        use.artifact for use in uses if use.declared_version == "(managed)"
    }
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
        "propertySafety": [asdict(item) for item in property_safety],
        "managedWithoutLocalVersion": unresolved,
        "changedPoms": sorted(
            {change.pom for change in property_changes}
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
