#!/usr/bin/env python3
"""Select the already published server-image version for runtime smoke tests."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any

CURRENT_RELEASE_PATH = Path("docs/current-release-version.txt")
RELEASE_CANDIDATE_PATH = Path(".github/release-candidate.json")
RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")


def _full_commit_sha(value: object, field: str) -> str:
    if not isinstance(value, str) or not COMMIT_SHA.fullmatch(value):
        raise ValueError(f"{field} must be a full lowercase commit SHA")
    return value


def _read_git_file(repository: Path, revision: str, path: Path) -> str:
    # Only a validated full hexadecimal object ID reaches Git. A revision that
    # starts with an option marker or contains revision/path syntax is rejected
    # before subprocess invocation.
    revision = _full_commit_sha(revision, "Git revision")
    result = subprocess.run(
        ["git", "-C", str(repository), "show", f"{revision}:{path.as_posix()}"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "git show failed"
        raise ValueError(
            f"Could not read {path.as_posix()} from {revision}: {detail}"
        )
    return result.stdout


def _release_candidate_source(repository: Path) -> str:
    candidate_path = repository / RELEASE_CANDIDATE_PATH
    if not candidate_path.is_file():
        raise ValueError(
            "Release-preparation workflow dispatch requires "
            f"{RELEASE_CANDIDATE_PATH.as_posix()}"
        )
    try:
        candidate: Any = json.loads(candidate_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Could not read release candidate metadata: {exc}") from exc
    if not isinstance(candidate, dict):
        raise ValueError("Release candidate metadata must be a JSON object")
    return _full_commit_sha(
        candidate.get("source_commit"),
        "Release candidate source_commit",
    )


def select_version(
    repository: Path,
    event_name: str,
    ref_name: str,
    base_sha: str | None = None,
) -> str:
    """Return the immutable image version that is already publicly available.

    Pull requests test the release documented by their base commit. Explicitly
    dispatched release-preparation checks use the candidate's recorded source
    commit because the candidate itself already documents the not-yet-published
    version. Other manual runs use the checked-out release document.
    """

    repository = repository.resolve()
    if event_name == "pull_request":
        validated_base = _full_commit_sha(base_sha, "Pull-request base_sha")
        raw_version = _read_git_file(
            repository, validated_base, CURRENT_RELEASE_PATH
        )
    elif event_name == "workflow_dispatch" and ref_name.startswith(
        "release/prepare-"
    ):
        source_commit = _release_candidate_source(repository)
        raw_version = _read_git_file(
            repository, source_commit, CURRENT_RELEASE_PATH
        )
    else:
        try:
            raw_version = (repository / CURRENT_RELEASE_PATH).read_text(
                encoding="utf-8"
            )
        except OSError as exc:
            raise ValueError(
                f"Could not read {CURRENT_RELEASE_PATH.as_posix()}: {exc}"
            ) from exc

    version = raw_version.strip()
    if not RELEASE_VERSION.fullmatch(version):
        raise ValueError(
            f"Published release version must match X.Y.Z, received {version!r}"
        )
    return version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--ref-name", required=True)
    parser.add_argument("--base-sha")
    args = parser.parse_args()
    try:
        version = select_version(
            args.repository,
            args.event_name,
            args.ref_name,
            args.base_sha,
        )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    print(version)


if __name__ == "__main__":
    main()
