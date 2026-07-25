#!/usr/bin/env python3
"""Validate a staged static Maven repository and emit audit evidence."""
from __future__ import annotations
import argparse, hashlib, json, re, sys
from pathlib import Path

GROUP = Path("io/github/carstenartur")
PARENT = "jgit-storage-hibernate-parent"
JARS = (
    "jgit-storage-hibernate-core",
    "jgit-storage-hibernate-search",
    "jgit-storage-hibernate-java-analysis",
    "jgit-storage-hibernate-architecture",
    "jgit-storage-hibernate-benchmarks",
)
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def required(version: str) -> list[Path]:
    result = [GROUP / PARENT / version / f"{PARENT}-{version}.pom"]
    for artifact in JARS:
        base = GROUP / artifact / version / f"{artifact}-{version}"
        result.extend([
            Path(f"{base}.pom"), Path(f"{base}.jar"),
            Path(f"{base}-sources.jar"), Path(f"{base}-javadoc.jar"),
        ])
    return result


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
        sha256 = hashlib.sha256(data).hexdigest()
        sha512 = hashlib.sha512(data).hexdigest()
        path.with_name(path.name + ".sha256").write_text(sha256 + "\n", encoding="ascii")
        path.with_name(path.name + ".sha512").write_text(sha512 + "\n", encoding="ascii")
        manifest_files.append({
            "path": relative.as_posix(), "size": len(data),
            "sha256": sha256, "sha512": sha512,
        })

    snapshots = [p for p in repository.rglob("*") if p.is_file() and "SNAPSHOT" in p.as_posix()]
    if snapshots:
        errors.append("SNAPSHOT files in release repository: " + ", ".join(str(p.relative_to(repository)) for p in snapshots[:20]))
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
        "files": sorted(manifest_files, key=lambda item: str(item["path"])),
    }
    args.evidence.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Verified static Maven repository {repository} for {args.version}: {len(manifest_files)} release files")

if __name__ == "__main__":
    main()
