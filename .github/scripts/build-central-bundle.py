#!/usr/bin/env python3
"""Build a Central-layout inspection bundle from an isolated Maven repository.

The official Central plugin remains responsible for real upload and publication. This
helper is only for credential-free CI: Maven first builds, signs and installs the exact
release coordinates into an empty local repository while Central upload is disabled;
this script then assembles those installed files and their checksums into the same
coordinate layout that Central validates.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

GROUP_PATH = Path("io/github/carstenartur")
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
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def artifact_files(version: str) -> dict[Path, str]:
    files: dict[Path, str] = {}

    parent_base = f"{PARENT}-{version}"
    parent_dir = GROUP_PATH / PARENT / version
    for suffix in (".pom", ".pom.asc"):
        relative = parent_dir / f"{parent_base}{suffix}"
        files[relative] = relative.as_posix()

    for artifact in JAR_ARTIFACTS:
        base = f"{artifact}-{version}"
        directory = GROUP_PATH / artifact / version
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
            relative = directory / f"{base}{suffix}"
            files[relative] = relative.as_posix()

    return files


def zip_entry(name: str, data: bytes) -> tuple[zipfile.ZipInfo, bytes]:
    info = zipfile.ZipInfo(name, date_time=ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info, data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version", help="Release version in X.Y.Z form")
    parser.add_argument("repository", type=Path, help="Isolated Maven local repository")
    parser.add_argument("output", type=Path, help="Bundle ZIP path")
    args = parser.parse_args()

    if not RELEASE_VERSION.fullmatch(args.version):
        raise SystemExit("version must use X.Y.Z without SNAPSHOT")
    if not args.repository.is_dir():
        raise SystemExit(f"Maven repository does not exist: {args.repository}")

    required = artifact_files(args.version)
    resolved: list[tuple[str, bytes]] = []
    missing: list[Path] = []
    for relative, archive_name in sorted(required.items(), key=lambda item: item[1]):
        source = args.repository / relative
        if not source.is_file():
            missing.append(source)
            continue
        data = source.read_bytes()
        if not data:
            raise SystemExit(f"Release artifact is empty: {source}")
        resolved.append((archive_name, data))

    if missing:
        print("Missing installed release artifacts:", file=sys.stderr)
        for path in missing:
            print(f"  {path}", file=sys.stderr)
        raise SystemExit(1)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.unlink(missing_ok=True)

    try:
        with zipfile.ZipFile(temporary, "w") as archive:
            for archive_name, data in resolved:
                info, payload = zip_entry(archive_name, data)
                archive.writestr(info, payload)
                for extension, constructor in CHECKSUMS:
                    digest = constructor(data).hexdigest().encode("ascii")
                    checksum_info, checksum_payload = zip_entry(
                        f"{archive_name}.{extension}", digest
                    )
                    archive.writestr(checksum_info, checksum_payload)
        temporary.replace(args.output)
    finally:
        temporary.unlink(missing_ok=True)

    print(
        f"Built credential-free Central inspection bundle for {args.version}: "
        f"{args.output} ({len(resolved)} signed release files)"
    )


if __name__ == "__main__":
    main()
