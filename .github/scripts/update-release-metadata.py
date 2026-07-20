#!/usr/bin/env python3
"""Update citation, software, and public documentation release metadata."""

from __future__ import annotations

import argparse
import json
import re
from datetime import date
from pathlib import Path

PROJECT_GROUP_ID = "io.github.carstenartur"
PROJECT_ARTIFACT_PREFIX = "jgit-storage-hibernate-"
DOCUMENTATION_VERSION_FILE = Path("docs/current-release-version.txt")
RELEASE_SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?$")
DEPENDENCY_BLOCK = re.compile(r"<dependency>.*?</dependency>", re.DOTALL)
PROJECT_COORDINATE = re.compile(
    r"(io\.github\.carstenartur:jgit-storage-hibernate-[A-Za-z0-9_.-]+:)"
    r"([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)"
)
DOCUMENTED_RELEASE_LINE = re.compile(
    r"(The documented release line is \*\*)([0-9]+\.[0-9]+\.[0-9]+)(\*\*)"
)
DOCUMENTATION_VERSION_PLACEHOLDERS = {
    "...",
    "X.Y.Z",
    "X.Y.Z-SNAPSHOT",
    "${project.version}",
    "${revision}",
}
PUBLIC_MARKDOWN_GLOBS = (
    "README.md",
    "docs/**/*.md",
    "jgit-storage-hibernate-*/README.md",
)


def replace(pattern: str, replacement: str, text: str, *, flags: int = 0) -> str:
    updated, count = re.subn(pattern, replacement, text, flags=flags)
    if count == 0:
        raise SystemExit(f"Pattern not found: {pattern}")
    return updated


def update_citation_cff(version: str, release: bool, today: str) -> None:
    path = Path("CITATION.cff")
    text = path.read_text(encoding="utf-8")
    text = replace(r'^version: ".*"$', f'version: "{version}"', text, flags=re.MULTILINE)
    text = re.sub(r"^date-released: .*\n", "", text, flags=re.MULTILINE)
    if release:
        text = text.replace(
            'version: "' + version + '"\n',
            'version: "' + version + '"\n' + f'date-released: "{today}"\n',
        )
    path.write_text(text, encoding="utf-8")


def update_citation_md(version: str, release: bool, today: str) -> None:
    path = Path("CITATION.md")
    text = path.read_text(encoding="utf-8")
    text = replace(
        r"Version [0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?\.",
        f"Version {version}.",
        text,
    )
    text = replace(r"version = \{[^}]+\}", f"version = {{{version}}}", text)
    text = re.sub(r"^\s*date\s+= \{[^}]+\},\n", "", text, flags=re.MULTILINE)
    if release:
        text = text.replace(
            f"  version = {{{version}}},\n",
            f"  version = {{{version}}},\n  date    = {{{today}}},\n",
        )
    path.write_text(text, encoding="utf-8")


def update_json(path_name: str, version: str, release: bool, today: str, date_key: str) -> None:
    path = Path(path_name)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["version"] = version
    if release:
        data[date_key] = today
    else:
        data.pop(date_key, None)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def markdown_files() -> list[Path]:
    paths: set[Path] = set()
    for pattern in PUBLIC_MARKDOWN_GLOBS:
        paths.update(path for path in Path(".").glob(pattern) if path.is_file())
    return sorted(path for path in paths if "docs/releases" not in path.as_posix())


def project_dependency(block: str) -> bool:
    group_match = re.search(r"<groupId>\s*([^<]+)\s*</groupId>", block)
    artifact_match = re.search(r"<artifactId>\s*([^<]+)\s*</artifactId>", block)
    if group_match is None or artifact_match is None:
        return False
    return (
        group_match.group(1).strip() == PROJECT_GROUP_ID
        and artifact_match.group(1).strip().startswith(PROJECT_ARTIFACT_PREFIX)
    )


def update_dependency_block(block: str, version: str) -> tuple[str, bool]:
    if not project_dependency(block):
        return block, False

    version_match = re.search(r"(<version>\s*)([^<]+)(\s*</version>)", block)
    if version_match is None:
        return block, False

    current = version_match.group(2).strip()
    if current in DOCUMENTATION_VERSION_PLACEHOLDERS:
        return block, False
    if not SEMVER.fullmatch(current):
        return block, False

    updated = (
        block[: version_match.start(2)]
        + version
        + block[version_match.end(2) :]
    )
    return updated, updated != block


def update_public_documentation(version: str) -> list[Path]:
    """Advance active public dependency examples to the release being created.

    Historical release notes are excluded. Other historical version references, such as the
    0.1.4 migration baseline, are preserved because only project dependency declarations,
    project coordinates, and explicit documented-release sentences are rewritten.
    """
    if not RELEASE_SEMVER.fullmatch(version):
        raise SystemExit(
            f"Public documentation requires a release version X.Y.Z, but received {version!r}"
        )
    if not DOCUMENTATION_VERSION_FILE.is_file():
        raise SystemExit(
            f"Missing documented release version file: {DOCUMENTATION_VERSION_FILE}"
        )

    previous = DOCUMENTATION_VERSION_FILE.read_text(encoding="utf-8").strip()
    if not RELEASE_SEMVER.fullmatch(previous):
        raise SystemExit(
            f"{DOCUMENTATION_VERSION_FILE} must contain the last published X.Y.Z version; "
            f"found {previous!r}"
        )

    changed: list[Path] = []
    for path in markdown_files():
        text = path.read_text(encoding="utf-8")
        dependency_changes = 0

        def dependency_replacement(match: re.Match[str]) -> str:
            nonlocal dependency_changes
            updated, did_change = update_dependency_block(match.group(0), version)
            if did_change:
                dependency_changes += 1
            return updated

        updated = DEPENDENCY_BLOCK.sub(dependency_replacement, text)
        updated, coordinate_changes = PROJECT_COORDINATE.subn(
            lambda match: match.group(1) + version,
            updated,
        )
        updated, release_line_changes = DOCUMENTED_RELEASE_LINE.subn(
            lambda match: match.group(1) + version + match.group(3),
            updated,
        )

        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed.append(path)

        if dependency_changes or coordinate_changes or release_line_changes:
            print(
                f"Updated {path}: dependencies={dependency_changes}, "
                f"coordinates={coordinate_changes}, release-lines={release_line_changes}"
            )

    DOCUMENTATION_VERSION_FILE.write_text(version + "\n", encoding="utf-8")
    if previous != version:
        changed.append(DOCUMENTATION_VERSION_FILE)
    print(f"Documented release advanced from {previous} to {version}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    today = date.today().isoformat()

    update_citation_cff(args.version, args.release, today)
    update_citation_md(args.version, args.release, today)
    update_json(".zenodo.json", args.version, args.release, today, "publication_date")
    update_json("codemeta.json", args.version, args.release, today, "datePublished")
    if args.release:
        update_public_documentation(args.version)


if __name__ == "__main__":
    main()
