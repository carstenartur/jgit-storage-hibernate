#!/usr/bin/env python3
"""Resume a release after its immutable Maven artifacts were already published.

The normal release state machine intentionally refuses to overwrite an existing
version.  This recovery path validates the existing repository byte-for-byte,
proves that no runtime source changed since the original publication, and then
continues only the missing tag, GitHub Release and next-development steps.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Iterable, Mapping, NoReturn, Sequence
import xml.etree.ElementTree as ET


class RecoveryError(RuntimeError):
    """Raised when the partial release cannot be resumed safely."""


RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
SNAPSHOT_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$")
HASH_ALGORITHMS = ("sha1", "sha256", "sha512")
ALLOWED_LINEAGE_EXACT = {
    ".zenodo.json",
    "CITATION.cff",
    "CITATION.md",
    "README.md",
    "SECURITY.md",
    "codemeta.json",
    "pom.xml",
}
ALLOWED_LINEAGE_PREFIXES = (".github/", "docs/")
ALLOWED_LINEAGE_SUFFIXES = ("/README.md", "/pom.xml")


def fail(message: str) -> NoReturn:
    raise RecoveryError(message)


def required_environment(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        fail(f"{name} is required")
    return value


def run(
    arguments: Sequence[str],
    *,
    cwd: Path,
    capture: bool = False,
    check: bool = True,
    env: Mapping[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    merged_environment = os.environ.copy()
    if env:
        merged_environment.update(env)
    return subprocess.run(
        list(arguments),
        cwd=cwd,
        check=check,
        text=True,
        capture_output=capture,
        env=merged_environment,
    )


def output(arguments: Sequence[str], *, cwd: Path) -> str:
    return run(arguments, cwd=cwd, capture=True).stdout.strip()


def project_version(pom: Path) -> str:
    root = ET.parse(pom).getroot()
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    version = root.findtext(f"{namespace}version")
    if version is None or not version.strip():
        fail(f"{pom} has no project version")
    return version.strip()


def numeric_version(version: str) -> tuple[int, int, int]:
    major, minor, patch = map(
        int, version.removesuffix("-SNAPSHOT").split(".")
    )
    return major, minor, patch


def validate_versions(release_version: str, next_version: str) -> None:
    if RELEASE_VERSION_PATTERN.fullmatch(release_version) is None:
        fail(f"Release version {release_version!r} must use X.Y.Z")
    if SNAPSHOT_VERSION_PATTERN.fullmatch(next_version) is None:
        fail(f"Next development version {next_version!r} must use X.Y.Z-SNAPSHOT")
    if numeric_version(next_version) <= numeric_version(release_version):
        fail(
            f"Next development version {next_version} must be newer than "
            f"release {release_version}"
        )


def allowed_lineage_path(value: str) -> bool:
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        return False
    normalized = path.as_posix()
    return (
        normalized in ALLOWED_LINEAGE_EXACT
        or normalized.startswith(ALLOWED_LINEAGE_PREFIXES)
        or normalized.endswith(ALLOWED_LINEAGE_SUFFIXES)
    )


def validate_source_lineage(
    repository: Path, published_source_commit: str, release_commit: str
) -> list[str]:
    for commit in (published_source_commit, release_commit):
        result = run(
            ["git", "cat-file", "-e", f"{commit}^{{commit}}"],
            cwd=repository,
            check=False,
            capture=True,
        )
        if result.returncode != 0:
            fail(f"Commit {commit!r} is not available in the release checkout")

    changed = [
        line
        for line in output(
            [
                "git",
                "diff",
                "--name-only",
                f"{published_source_commit}..{release_commit}",
            ],
            cwd=repository,
        ).splitlines()
        if line
    ]
    disallowed = [path for path in changed if not allowed_lineage_path(path)]
    if disallowed:
        fail(
            "Runtime or non-release files changed after the published source commit: "
            + ", ".join(disallowed)
        )
    return changed


def secure_relative_path(value: Any) -> PurePosixPath:
    if not isinstance(value, str) or not value:
        fail(f"Manifest path must be a non-empty string, found {value!r}")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or path.as_posix() != value:
        fail(f"Unsafe manifest path {value!r}")
    return path


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def validate_manifest(
    public_repository: Path, manifest_path: Path, release_version: str
) -> list[dict[str, Any]]:
    if not manifest_path.is_file():
        fail(f"Missing immutable release manifest {manifest_path}")
    try:
        document = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Cannot read release manifest {manifest_path}: {error}")
    if not isinstance(document, dict):
        fail("Release manifest must contain a JSON object")

    files = document.get("files")
    if not isinstance(files, list) or not files:
        fail("Release manifest must contain a non-empty files array")

    declared_algorithms: set[str] = set()
    for key in ("canonicalChecksums", "compatibilityChecksums"):
        values = document.get(key, [])
        if not isinstance(values, list) or not all(isinstance(item, str) for item in values):
            fail(f"Manifest field {key} must be an array of checksum names")
        declared_algorithms.update(values)
    if declared_algorithms != set(HASH_ALGORITHMS):
        fail(
            "Release manifest must declare exactly sha1, sha256 and sha512 checksums"
        )

    validated: list[dict[str, Any]] = []
    seen_paths: set[str] = set()
    for raw_entry in files:
        if not isinstance(raw_entry, dict):
            fail(f"Manifest file entry must be an object, found {raw_entry!r}")
        relative = secure_relative_path(raw_entry.get("path"))
        relative_text = relative.as_posix()
        if relative_text in seen_paths:
            fail(f"Duplicate manifest path {relative_text}")
        seen_paths.add(relative_text)
        if len(relative.parts) < 3 or relative.parts[-2] != release_version:
            fail(
                f"Manifest path {relative_text} does not belong to version "
                f"{release_version}"
            )

        artifact = public_repository.joinpath(*relative.parts)
        if not artifact.is_file():
            fail(f"Published artifact is missing: {relative_text}")
        expected_size = raw_entry.get("size")
        if not isinstance(expected_size, int) or expected_size < 0:
            fail(f"Invalid size for {relative_text}: {expected_size!r}")
        actual_size = artifact.stat().st_size
        if actual_size != expected_size:
            fail(
                f"Size mismatch for {relative_text}: expected {expected_size}, "
                f"found {actual_size}"
            )

        for algorithm in HASH_ALGORITHMS:
            expected = raw_entry.get(algorithm)
            expected_length = hashlib.new(algorithm).digest_size * 2
            if (
                not isinstance(expected, str)
                or len(expected) != expected_length
                or re.fullmatch(r"[0-9a-f]+", expected) is None
            ):
                fail(f"Invalid {algorithm} checksum for {relative_text}")
            actual = digest(artifact, algorithm)
            if actual != expected:
                fail(
                    f"{algorithm} mismatch for {relative_text}: expected "
                    f"{expected}, found {actual}"
                )
        validated.append(dict(raw_entry))
    return validated


def copy_release_assets(
    repository: Path,
    public_repository: Path,
    manifest_path: Path,
    entries: Iterable[Mapping[str, Any]],
    release_version: str,
    destination: Path,
    published_source_commit: str,
    release_commit: str,
) -> list[Path]:
    shutil.rmtree(destination, ignore_errors=True)
    destination.mkdir(parents=True)
    copied_names: set[str] = set()

    for entry in entries:
        relative = secure_relative_path(entry["path"])
        source = public_repository.joinpath(*relative.parts)
        if source.suffix not in {".jar", ".pom"}:
            continue
        name = source.name
        if name in copied_names:
            name = "__".join(relative.parts[-3:])
        if name in copied_names:
            fail(f"Release asset name collision for {relative.as_posix()}")
        copied_names.add(name)
        shutil.copy2(source, destination / name)

    manifest_asset = destination / f"maven-repository-manifest-{release_version}.json"
    shutil.copy2(manifest_path, manifest_asset)
    copied_names.add(manifest_asset.name)

    for name in (".zenodo.json", "CITATION.cff", "CITATION.md", "codemeta.json"):
        source = repository / name
        if source.is_file():
            target_name = name.removeprefix(".")
            if target_name in copied_names:
                fail(f"Release asset name collision for {name}")
            copied_names.add(target_name)
            shutil.copy2(source, destination / target_name)

    evidence = {
        "releaseVersion": release_version,
        "releaseCommit": release_commit,
        "publishedSourceCommit": published_source_commit,
        "immutableManifestSha256": digest(manifest_path, "sha256"),
    }
    (destination / "release-recovery-evidence.json").write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    assets = sorted(path for path in destination.iterdir() if path.is_file())
    if not assets:
        fail("No GitHub Release assets were prepared")
    return assets


def configure_git(repository: Path) -> None:
    run(["git", "config", "user.name", "github-actions[bot]"], cwd=repository)
    run(
        [
            "git",
            "config",
            "user.email",
            "41898282+github-actions[bot]@users.noreply.github.com",
        ],
        cwd=repository,
    )


def ensure_tag(repository: Path, tag_name: str, release_commit: str) -> None:
    run(["git", "fetch", "origin", "--tags", "--force"], cwd=repository)
    result = run(
        ["git", "rev-parse", "--verify", f"{tag_name}^{{commit}}"],
        cwd=repository,
        capture=True,
        check=False,
    )
    if result.returncode == 0:
        existing = result.stdout.strip()
        if existing != release_commit:
            fail(
                f"Tag {tag_name} points to {existing} instead of "
                f"release commit {release_commit}"
            )
        print(f"Tag {tag_name} already points to the release commit.")
        return
    run(
        ["git", "tag", "-a", tag_name, release_commit, "-m", f"Release {tag_name}"],
        cwd=repository,
    )
    run(["git", "push", "origin", f"refs/tags/{tag_name}"], cwd=repository)


def ensure_github_release(
    repository: Path,
    repository_name: str,
    tag_name: str,
    release_version: str,
    assets: Sequence[Path],
) -> None:
    existing = run(
        ["gh", "release", "view", tag_name, "--repo", repository_name],
        cwd=repository,
        capture=True,
        check=False,
    )
    if existing.returncode == 0:
        print(f"GitHub Release {tag_name} already exists.")
        return
    arguments = [
        "gh",
        "release",
        "create",
        tag_name,
        "--repo",
        repository_name,
        "--title",
        f"jgit-storage-hibernate {release_version}",
        "--verify-tag",
        "--fail-on-no-commits",
        "--generate-notes",
    ]
    arguments.extend(str(asset) for asset in assets)
    run(arguments, cwd=repository)


def push_replaceable_branch(repository: Path, branch: str) -> None:
    remote = run(
        ["git", "ls-remote", "--exit-code", "--heads", "origin", f"refs/heads/{branch}"],
        cwd=repository,
        capture=True,
        check=False,
    )
    if remote.returncode == 0:
        run(
            [
                "git",
                "fetch",
                "origin",
                f"refs/heads/{branch}:refs/remotes/origin/{branch}",
            ],
            cwd=repository,
        )
        expected = output(["git", "rev-parse", f"refs/remotes/origin/{branch}"], cwd=repository)
        run(
            [
                "git",
                "push",
                f"--force-with-lease=refs/heads/{branch}:{expected}",
                "origin",
                f"HEAD:refs/heads/{branch}",
            ],
            cwd=repository,
        )
    else:
        run(["git", "push", "origin", f"HEAD:refs/heads/{branch}"], cwd=repository)


def prepare_next_development(
    repository: Path,
    release_commit: str,
    release_version: str,
    next_version: str,
) -> str:
    branch = f"release/next-{next_version.removesuffix('-SNAPSHOT')}"
    run(["git", "switch", "-C", branch, release_commit], cwd=repository)
    run(
        [
            "mvn",
            "-B",
            "versions:set",
            f"-DnewVersion={next_version}",
            "-DprocessAllModules=true",
            "-DgenerateBackupPoms=false",
        ],
        cwd=repository,
    )
    run(
        [
            "python3",
            ".github/scripts/update-release-metadata.py",
            next_version,
        ],
        cwd=repository,
    )
    candidate = repository / ".github/release-candidate.json"
    candidate.unlink(missing_ok=True)

    run(["python3", ".github/scripts/verify-release-consistency.py"], cwd=repository)
    run(
        ["python3", ".github/scripts/verify-public-repository-publishing.py"],
        cwd=repository,
    )
    run(["git", "diff", "--check"], cwd=repository)
    run(["git", "add", "-A"], cwd=repository)
    diff = run(["git", "diff", "--cached", "--quiet"], cwd=repository, check=False)
    if diff.returncode == 0:
        print(f"Next-development branch {branch} already has the requested content.")
    elif diff.returncode == 1:
        run(
            [
                "git",
                "commit",
                "-m",
                f"Prepare next development version {next_version}",
            ],
            cwd=repository,
        )
    else:
        fail("Cannot inspect staged next-development changes")
    push_replaceable_branch(repository, branch)
    return branch


def append_summary(lines: Sequence[str]) -> None:
    summary = os.environ.get("GITHUB_STEP_SUMMARY", "").strip()
    if not summary:
        return
    with Path(summary).open("a", encoding="utf-8") as handle:
        for line in lines:
            print(line, file=handle)


def main() -> int:
    repository = Path.cwd().resolve()
    release_version = required_environment("RELEASE_VERSION")
    next_version = required_environment("NEXT_DEVELOPMENT_VERSION")
    release_commit_input = required_environment("RELEASE_COMMIT")
    published_source_commit_input = required_environment("PUBLISHED_SOURCE_COMMIT")
    repository_name = required_environment("GITHUB_REPOSITORY")
    public_branch = os.environ.get("PUBLIC_REPOSITORY_BRANCH", "maven-repository").strip()
    public_url = os.environ.get(
        "PUBLIC_REPOSITORY_URL",
        "https://raw.githubusercontent.com/"
        f"{repository_name}/{public_branch}/",
    ).strip()
    validate_versions(release_version, next_version)

    configure_git(repository)
    run(["git", "fetch", "origin", "--tags", "--force"], cwd=repository)
    release_commit = output(
        ["git", "rev-parse", f"{release_commit_input}^{{commit}}"], cwd=repository
    )
    published_source_commit = output(
        ["git", "rev-parse", f"{published_source_commit_input}^{{commit}}"],
        cwd=repository,
    )
    head = output(["git", "rev-parse", "HEAD"], cwd=repository)
    if head != release_commit:
        fail(f"Recovery checkout is {head}; expected release commit {release_commit}")
    current_version = project_version(repository / "pom.xml")
    if current_version != release_version:
        fail(
            f"Release commit has Maven version {current_version}; "
            f"expected {release_version}"
        )

    changed = validate_source_lineage(
        repository, published_source_commit, release_commit
    )
    print(
        "Validated release-only lineage changes: "
        + (", ".join(changed) if changed else "none")
    )

    public_worktree_parent = Path(tempfile.mkdtemp(prefix="jgit-release-recovery-"))
    public_worktree = public_worktree_parent / "maven-repository"
    try:
        run(
            [
                "git",
                "fetch",
                "origin",
                f"refs/heads/{public_branch}:refs/remotes/origin/{public_branch}",
            ],
            cwd=repository,
        )
        run(
            [
                "git",
                "worktree",
                "add",
                "--detach",
                str(public_worktree),
                f"refs/remotes/origin/{public_branch}",
            ],
            cwd=repository,
        )
        manifest_path = public_worktree / "releases" / f"{release_version}.json"
        entries = validate_manifest(public_worktree, manifest_path, release_version)

        verifier = repository / ".github/scripts/verify-public-repository-consumption.sh"
        run(
            [str(verifier), release_version, public_url],
            cwd=repository,
            env={
                "PUBLIC_REPOSITORY_ATTEMPTS": os.environ.get(
                    "PUBLIC_REPOSITORY_ATTEMPTS", "12"
                ),
                "PUBLIC_REPOSITORY_RETRY_SECONDS": os.environ.get(
                    "PUBLIC_REPOSITORY_RETRY_SECONDS", "10"
                ),
            },
        )

        assets = copy_release_assets(
            repository,
            public_worktree,
            manifest_path,
            entries,
            release_version,
            public_worktree_parent / "release-assets",
            published_source_commit,
            release_commit,
        )
        tag_name = f"v{release_version}"
        ensure_tag(repository, tag_name, release_commit)
        ensure_github_release(
            repository,
            repository_name,
            tag_name,
            release_version,
            assets,
        )
    finally:
        if public_worktree.exists():
            run(
                ["git", "worktree", "remove", "--force", str(public_worktree)],
                cwd=repository,
                check=False,
            )
        shutil.rmtree(public_worktree_parent, ignore_errors=True)

    next_branch = prepare_next_development(
        repository, release_commit, release_version, next_version
    )
    append_summary(
        [
            (
                f"Recovered immutable release `v{release_version}` from the "
                "existing public Maven repository."
            ),
            (
                f"Validated published source `{published_source_commit}` "
                f"against release commit `{release_commit}`."
            ),
            f"Prepared next-development branch `{next_branch}` for `{next_version}`.",
        ]
    )
    print(
        f"Recovered v{release_version}; next development branch is {next_branch}."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RecoveryError as error:
        print(f"::error::{error}", file=sys.stderr)
        raise SystemExit(1)
