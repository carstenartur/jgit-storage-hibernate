#!/usr/bin/env python3
"""Validate the structure, signatures and checksums of a Central publishing bundle."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

GROUP_PATH = "io/github/carstenartur"
PARENT = "jgit-storage-hibernate-parent"
JAR_ARTIFACTS = (
    "jgit-storage-hibernate-core",
    "jgit-storage-hibernate-search",
    "jgit-storage-hibernate-java-analysis",
    "jgit-storage-hibernate-architecture",
    "jgit-storage-hibernate-benchmarks",
)
RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
CHECKSUMS = (
    ("md5", hashlib.md5),
    ("sha1", hashlib.sha1),
    ("sha256", hashlib.sha256),
    ("sha512", hashlib.sha512),
)


def signed_entries(version: str) -> set[str]:
    entries: set[str] = set()
    parent_base = f"{GROUP_PATH}/{PARENT}/{version}/{PARENT}-{version}"
    entries.update({f"{parent_base}.pom", f"{parent_base}.pom.asc"})

    for artifact in JAR_ARTIFACTS:
        base = f"{GROUP_PATH}/{artifact}/{version}/{artifact}-{version}"
        for suffix in (
            ".pom",
            ".pom.asc",
            ".jar",
            ".jar.asc",
            "-sources.jar",
            "-sources.jar.asc",
            "-javadoc.jar",
            "-javadoc.jar.asc",
        ):
            entries.add(base + suffix)
    return entries


def required_entries(version: str) -> set[str]:
    entries = signed_entries(version)
    for name in tuple(entries):
        for extension, _ in CHECKSUMS:
            entries.add(f"{name}.{extension}")
    return entries


def locate_bundle(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit
    candidates = sorted(Path("target/central-publishing").glob("*.zip"))
    if len(candidates) != 1:
        raise SystemExit(
            "Expected exactly one target/central-publishing/*.zip bundle; "
            f"found {len(candidates)}"
        )
    return candidates[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version", help="Release version in X.Y.Z form")
    parser.add_argument("bundle", nargs="?", type=Path)
    args = parser.parse_args()

    if not RELEASE_VERSION.fullmatch(args.version):
        raise SystemExit("version must use X.Y.Z without SNAPSHOT")

    bundle = locate_bundle(args.bundle)
    if not bundle.is_file():
        raise SystemExit(f"Central bundle not found: {bundle}")

    errors: list[str] = []
    signed = signed_entries(args.version)
    required = required_entries(args.version)
    with zipfile.ZipFile(bundle) as archive:
        names = set(archive.namelist())
        missing = sorted(required - names)
        if missing:
            errors.append("missing required bundle entries:\n  " + "\n  ".join(missing))

        snapshots = sorted(name for name in names if "SNAPSHOT" in name)
        if snapshots:
            errors.append("bundle contains SNAPSHOT paths:\n  " + "\n  ".join(snapshots))

        for name in sorted(signed & names):
            data = archive.read(name)
            if not data:
                errors.append(f"empty release file: {name}")
                continue
            if name.endswith((".pom", ".jar")) and f"{name}.asc" not in names:
                errors.append(f"missing signature for {name}")
            if name.endswith(".asc") and archive.getinfo(name).file_size == 0:
                errors.append(f"empty signature file: {name}")

            for extension, constructor in CHECKSUMS:
                checksum_name = f"{name}.{extension}"
                if checksum_name not in names:
                    continue
                expected = constructor(data).hexdigest().lower()
                actual = archive.read(checksum_name).decode("ascii").strip().lower()
                if actual != expected:
                    errors.append(
                        f"invalid {extension} checksum for {name}: "
                        f"expected {expected}, found {actual}"
                    )

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        f"Central bundle verified for {args.version}: {bundle} "
        f"({len(signed)} signed files and {len(required) - len(signed)} checksums)"
    )


if __name__ == "__main__":
    main()
