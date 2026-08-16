#!/usr/bin/env python3
"""Validate and canonicalize a staged static Maven repository."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

GROUP = Path("io/github/carstenartur")
PARENT = "jgit-storage-hibernate-parent"
POMS = ("jgit-storage-hibernate-bom",)
JARS = (
    "jgit-storage-hibernate-core",
    "jgit-storage-hibernate-security",
    "jgit-storage-hibernate-smart-http",
    "jgit-storage-hibernate-search",
    "jgit-storage-hibernate-java-analysis",
    "jgit-storage-hibernate-architecture",
    "jgit-storage-hibernate-benchmarks",
)
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
VOLATILE_NAMES = {"_remote.repositories"}
# Maven-generated MD5 and transfer-state files are discarded. SHA-1 is
# regenerated as a compatibility sidecar because Maven Resolver still uses it
# for transport validation on simple static HTTP repositories. SHA-256/SHA-512
# remain the canonical strong verification algorithms.
VOLATILE_SUFFIXES = (".md5", ".lastUpdated")


def required(version: str) -> list[Path]:
    result = [GROUP / PARENT / version / f"{PARENT}-{version}.pom"]
    for artifact in POMS:
        result.append(GROUP / artifact / version / f"{artifact}-{version}.pom")
    for artifact in JARS:
        base = GROUP / artifact / version / f"{artifact}-{version}"
        result.extend([
            Path(f"{base}.pom"),
            Path(f"{base}.jar"),
            Path(f"{base}-sources.jar"),
            Path(f"{base}-javadoc.jar"),
        ])
    return result


def is_volatile(path: Path) -> bool:
    return path.name in VOLATILE_NAMES or path.name.endswith(VOLATILE_SUFFIXES)


def remove_volatile_files(repository: Path) -> list[Path]:
    removed: list[Path] = []
    for path in repository.rglob("*"):
        if path.is_file() and is_volatile(path):
            removed.append(path.relative_to(repository))
            path.unlink()
    return removed


def write_checksum_sidecars(path: Path, data: bytes) -> dict[str, str]:
    checksums = {
        "sha1": hashlib.sha1(data, usedforsecurity=False).hexdigest(),
        "sha256": hashlib.sha256(data).hexdigest(),
        "sha512": hashlib.sha512(data).hexdigest(),
    }
    for algorithm, digest in checksums.items():
        path.with_name(path.name + f".{algorithm}").write_text(
            digest + "\n", encoding="ascii"
        )
    return checksums


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("repository", type=Path)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    if not VERSION.fullmatch(args.version):
        raise SystemExit("version must use X.Y.Z")
    repository = args.repository.resolve()
    if not repository.is_dir():
        raise SystemExit(f"repository does not exist: {repository}")

    removed = remove_volatile_files(repository)
    errors: list[str] = []
    manifest_files: list[dict[str, object]] = []
    for relative in required(args.version):
        path = repository / relative
        if not path.is_file():
            errors.append(f"missing {relative.as_posix()}")
            continue
        data = path.read_bytes()
        if not data:
            errors.append(f"empty {relative.as_posix()}")
            continue
        checksums = write_checksum_sidecars(path, data)
        manifest_files.append({
            "path": relative.as_posix(),
            "size": len(data),
            **checksums,
        })

    snapshots = [
        path for path in repository.rglob("*")
        if path.is_file() and "SNAPSHOT" in path.as_posix()
    ]
    if snapshots:
        errors.append(
            "SNAPSHOT files in release repository: "
            + ", ".join(str(path.relative_to(repository)) for path in snapshots[:20])
        )
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

    (repository / ".nojekyll").write_text("", encoding="utf-8")
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schemaVersion": 1,
        "groupId": "io.github.carstenartur",
        "version": args.version,
        "canonicalChecksums": ["sha256", "sha512"],
        "compatibilityChecksums": ["sha1"],
        "removedVolatileFiles": sorted(path.as_posix() for path in removed),
        "files": sorted(manifest_files, key=lambda item: str(item["path"])),
    }
    args.evidence.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"Verified static Maven repository {repository} for {args.version}: "
        f"{len(manifest_files)} release files; removed {len(removed)} volatile files"
    )


if __name__ == "__main__":
    main()
