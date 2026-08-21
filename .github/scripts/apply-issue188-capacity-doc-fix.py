#!/usr/bin/env python3
"""Align pack-layout documentation with the issue #188 fail-closed contract."""

from pathlib import Path

path = Path("docs/operations/pack-storage-layout.md")
text = path.read_text(encoding="utf-8")
replacements = {
    "- `capacity`: explicit 512-MiB write and sequential-read evidence.": (
        "- `capacity`: explicit 512-MiB write, sequential, short and deterministic "
        "random-read evidence; sparse reads use one representative one-MiB "
        "read-ahead window to keep the profile bounded."
    ),
    (
        "When both production-database jobs succeed, a separate aggregate job "
        "downloads the two raw JMH artifacts, verifies that both are non-empty, "
        "merges them and invokes the decision converter once. A single-database "
        "result cannot promote a candidate."
    ): (
        "When both production-database jobs succeed, a separate aggregate job "
        "downloads the two raw JMH artifacts, verifies that both are non-empty, "
        "merges them and invokes the decision converter once. A single-database "
        "result cannot promote a candidate. The decision also requires sparse-read "
        "evidence for every candidate on both production databases; missing sparse "
        "evidence fails closed rather than being treated as zero regression."
    ),
    (
        "A write-only improvement is insufficient. A candidate is eligible for "
        "later format design only when both PostgreSQL and SQL Server show write "
        "and sequential-read gains and sparse reads regress by no more than five "
        "percent."
    ): (
        "A write-only improvement is insufficient. A candidate is eligible for "
        "later format design only when both PostgreSQL and SQL Server contain "
        "write, sequential-read and sparse-read comparisons, show write and "
        "sequential-read gains, and sparse reads regress by no more than five "
        "percent."
    ),
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one documentation match: {old!r}")
    text = text.replace(old, new)
path.write_text(text, encoding="utf-8")
