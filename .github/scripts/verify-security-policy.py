#!/usr/bin/env python3
"""Reject placeholder or release-inconsistent security policy content."""

from __future__ import annotations

import re
import sys
from pathlib import Path

POLICY = Path("SECURITY.md")
DOCUMENTED_VERSION = Path("docs/current-release-version.txt")
PLACEHOLDERS = (
    "Use this section to tell people",
    "Tell them where to go",
    "5.1.x",
    "5.0.x",
    "4.0.x",
)


def main() -> int:
    errors: list[str] = []
    policy = POLICY.read_text(encoding="utf-8") if POLICY.is_file() else ""
    version = (
        DOCUMENTED_VERSION.read_text(encoding="utf-8").strip()
        if DOCUMENTED_VERSION.is_file()
        else ""
    )

    if not policy:
        errors.append("SECURITY.md is missing or empty")
    for placeholder in PLACEHOLDERS:
        if placeholder in policy:
            errors.append(f"SECURITY.md contains placeholder or fictitious content: {placeholder!r}")

    match = re.fullmatch(r"(\d+)\.(\d+)\.\d+", version)
    if match is None:
        errors.append(f"invalid documented release version: {version!r}")
    else:
        release_line = f"{match.group(1)}.{match.group(2)}.x"
        if release_line not in policy:
            errors.append(
                f"SECURITY.md does not name the documented release line {release_line!r}"
            )

    required_phrases = (
        "Report a vulnerability",
        "private repository security advisory",
        "do not disclose",
    )
    lowered = policy.lower()
    for phrase in required_phrases:
        if phrase.lower() not in lowered:
            errors.append(f"SECURITY.md is missing required guidance: {phrase!r}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"Security policy is consistent with release line {version}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
