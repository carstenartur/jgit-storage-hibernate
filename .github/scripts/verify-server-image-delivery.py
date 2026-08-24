#!/usr/bin/env python3
"""Verify the runnable server image, Compose and documentation delivery contract."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(relative: str, errors: list[str]) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing required server-image file: {relative}")
        return ""
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        errors.append(f"server-image file is empty: {relative}")
    return text


def require(text: str, needle: str, relative: str, errors: list[str]) -> None:
    if needle not in text:
        errors.append(f"{relative} is missing required contract text: {needle!r}")


def reject(text: str, needle: str, relative: str, errors: list[str]) -> None:
    if needle in text:
        errors.append(f"{relative} contains forbidden contract text: {needle!r}")


def main() -> int:
    errors: list[str] = []

    compose = read("compose.yaml", errors)
    compose_build = read("compose.build.yaml", errors)
    edge_workflow = read(".github/workflows/server-image.yml", errors)
    release_workflow = read(".github/workflows/server-image-release.yml", errors)
    guide = read("docs/operations/server-image.md", errors)
    module_readme = read("jgit-storage-hibernate-server/README.md", errors)
    testcontainers_readme = read("jgit-storage-hibernate-testcontainers/README.md", errors)
    root_readme = read("README.md", errors)
    documented_version = read("docs/current-release-version.txt", errors).strip()

    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", documented_version):
        errors.append(
            "docs/current-release-version.txt must contain an immutable X.Y.Z release"
        )
    image_reference = re.compile(
        r"ghcr\.io/carstenartur/jgit-storage-hibernate-server:"
        r"([0-9]+\.[0-9]+\.[0-9]+)"
    )
    for relative, text in (
        ("README.md", root_readme),
        ("docs/operations/server-image.md", guide),
        ("jgit-storage-hibernate-server/README.md", module_readme),
        ("jgit-storage-hibernate-testcontainers/README.md", testcontainers_readme),
    ):
        for referenced_version in image_reference.findall(text):
            if referenced_version != documented_version:
                errors.append(
                    f"{relative} references server image {referenced_version}, "
                    f"but the documented release is {documented_version}"
                )

    require(
        compose,
        "${JSH_IMAGE:-ghcr.io/carstenartur/jgit-storage-hibernate-server:latest}",
        "compose.yaml",
        errors,
    )
    require(
        compose,
        "${JSH_DATABASE_PASSWORD:?Set JSH_DATABASE_PASSWORD}",
        "compose.yaml",
        errors,
    )
    require(
        compose,
        "${JSH_ADMIN_PASSWORD:?Set JSH_ADMIN_PASSWORD}",
        "compose.yaml",
        errors,
    )
    reject(compose, "    build:\n", "compose.yaml", errors)
    require(compose_build, "    build:\n", "compose.build.yaml", errors)
    require(
        compose_build,
        "dockerfile: jgit-storage-hibernate-server/Dockerfile",
        "compose.build.yaml",
        errors,
    )

    for workflow, relative in (
        (edge_workflow, ".github/workflows/server-image.yml"),
        (release_workflow, ".github/workflows/server-image-release.yml"),
    ):
        require(workflow, "packages: write", relative, errors)
        require(workflow, "docker/build-push-action@", relative, errors)
        require(workflow, "provenance: mode=max", relative, errors)
        require(workflow, "sbom: true", relative, errors)
        require(workflow, "Anonymous pull", relative, errors)
        require(workflow, "GHCR package is not public", relative, errors)
        require(workflow, "Change visibility", relative, errors)
        require(workflow, "GITHUB_STEP_SUMMARY", relative, errors)
        require(workflow, "verify-server-image-delivery.py", relative, errors)

    require(edge_workflow, "value=edge", ".github/workflows/server-image.yml", errors)
    require(edge_workflow, "value=sha-", ".github/workflows/server-image.yml", errors)
    require(
        edge_workflow,
        "compose.yaml -f compose.build.yaml",
        ".github/workflows/server-image.yml",
        errors,
    )
    require(
        release_workflow,
        "workflows: [ Release ]",
        ".github/workflows/server-image-release.yml",
        errors,
    )
    require(
        release_workflow,
        "docs/current-release-version.txt",
        ".github/workflows/server-image-release.yml",
        errors,
    )
    require(
        release_workflow,
        'git show "$release_tag:pom.xml"',
        ".github/workflows/server-image-release.yml",
        errors,
    )
    require(
        release_workflow,
        "value=latest",
        ".github/workflows/server-image-release.yml",
        errors,
    )

    for relative, text in (
        ("docs/operations/server-image.md", guide),
        ("jgit-storage-hibernate-server/README.md", module_readme),
    ):
        require(text, "Git Smart HTTP", relative, errors)
        require(text, "Drop-in", relative, errors)
        require(text, "Git LFS", relative, errors)
        require(text, "single-admin", relative, errors)

    require(guide, "`X.Y.Z`", "docs/operations/server-image.md", errors)
    require(guide, "`edge`", "docs/operations/server-image.md", errors)
    require(guide, "`latest`", "docs/operations/server-image.md", errors)
    require(
        guide,
        "One-time GHCR visibility bootstrap",
        "docs/operations/server-image.md",
        errors,
    )
    require(guide, "Change visibility", "docs/operations/server-image.md", errors)
    require(
        testcontainers_readme,
        "Stable suites must pin an immutable full release tag or digest",
        "jgit-storage-hibernate-testcontainers/README.md",
        errors,
    )
    require(
        root_readme,
        "Standalone Docker/OCI server",
        "README.md",
        errors,
    )

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Server image delivery, Compose and documentation contracts are consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
