#!/usr/bin/env python3
"""Fail when a self-removing repair workflow is still present."""

from __future__ import annotations

import argparse
from pathlib import Path


def temporary_workflows(root: Path) -> list[Path]:
    workflows = root / ".github" / "workflows"
    if not workflows.is_dir():
        return []
    return sorted(
        path.relative_to(root)
        for path in workflows.glob("temporary-*.yml")
        if path.is_file()
    ) + sorted(
        path.relative_to(root)
        for path in workflows.glob("temporary-*.yaml")
        if path.is_file()
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    found = temporary_workflows(args.root.resolve())
    if found:
        raise SystemExit(
            "Temporary repair workflows must be removed before merge:\n- "
            + "\n- ".join(path.as_posix() for path in found)
        )
    print("No temporary repair workflows remain")


if __name__ == "__main__":
    main()
