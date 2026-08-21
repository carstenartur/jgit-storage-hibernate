#!/usr/bin/env python3
"""Record the final complete pack-layout benchmark decision for issue #188."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


pack_path = Path("docs/operations/pack-storage-layout.md")
pack = pack_path.read_text(encoding="utf-8")
pack = replace_once(
    pack,
    (
        "The current production format stores payloads up to 256 KiB in `git_packs.data`. "
        "Larger payloads use one-MiB rows in `git_pack_chunks`. These defaults are stable "
        "and remain unchanged while alternative layouts are measured."
    ),
    (
        "The current production format stores payloads up to 256 KiB in `git_packs.data`. "
        "Larger payloads use one-MiB rows in `git_pack_chunks`. The completed PostgreSQL "
        "and SQL Server evidence retains these values as the production decision: no "
        "alternative chunk size provides a net benefit across sequential and sparse access."
    ),
    "pack-layout introduction",
)
pack = replace_once(
    pack,
    "## Current preliminary observations",
    "## Retained observations and final capacity decision",
    "observations heading",
)
pack = replace_once(
    pack,
    "The retained full and capacity matrices remain authoritative for the final decision.",
    """The retained full matrix and the final [complete 512-MiB capacity run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/32505334226) are now complete. The capacity aggregate contains 64 validated PostgreSQL and SQL Server rows: write, sequential read, 64-KiB short read and deterministic random 4-KiB reads for every candidate chunk size. Both sparse operations are present on both production databases, and the converted decision artifacts are strict JSON.

### Final 512-MiB cross-database comparison

Positive values are improvements relative to the current one-MiB layout. “Worst sparse” is the worse result across short and random reads; the promotion budget permits no regression below -5%.

| Chunk size | PostgreSQL write | PostgreSQL sequential | PostgreSQL worst sparse | SQL Server write | SQL Server sequential | SQL Server worst sparse | Decision |
|---:|---:|---:|---:|---:|---:|---:|---|
| 256 KiB | -8.25% | -14.18% | +21.31% | -7.67% | -5.68% | +16.53% | reject: write and sequential regress |
| 1 MiB | baseline | baseline | baseline | baseline | baseline | baseline | retain current layout |
| 2 MiB | +5.60% | +0.62% | -36.99% | +32.32% | +1.95% | -170.91% | reject: sparse regression |
| 4 MiB | +16.63% | +12.28% | -95.05% | +22.43% | +5.43% | -105.31% | reject: sparse regression |

The result explains the trade-off rather than merely selecting a winner. Smaller chunks reduce sparse overfetch but increase row, statement and sequential-transfer cost. Larger chunks reduce row/JDBC overhead and improve large writes and sequential reads, but sparse reads fetch far more payload than requested. The regression is large and consistent enough that neither a global larger default nor a special large-PACK layout is justified by this evidence.

The machine decision remains `retain-current-layout-pending-postgresql-and-sqlserver-evidence`; in this completed matrix that label means that no candidate passed, not that a production-database run is still missing. The authoritative operational conclusion is therefore to retain the current layout.""",
    "preliminary final-decision sentence",
)
pack = replace_once(
    pack,
    (
        "SQL Server execution, calibrated PostgreSQL RTT evidence and the bounded "
        "1/4/16-worker concurrency contract are now part of the benchmark workflow, "
        "and a fail-closed cross-database aggregate exists. The remaining evidence work "
        "is the retained full and capacity execution before issue #188 can close."
    ),
    (
        "SQL Server execution, calibrated PostgreSQL RTT evidence and the bounded "
        "1/4/16-worker concurrency contract are part of the benchmark workflow, and the "
        "retained full and complete sparse-aware capacity executions have succeeded. The "
        "cross-database aggregate rejects every alternative layout, so no schema migration "
        "or mixed-layout implementation is required for the current production decision."
    ),
    "remaining evidence paragraph",
)
pack = replace_once(
    pack,
    (
        "## Production decision\n\nNo production default changes in this benchmark slice. "
        "The authoritative values remain one-MiB chunks and a 256-KiB inline threshold. "
        "A later implementation PR is justified only by retained net-benefit evidence and "
        "must introduce the additive versioned layout contract above together with old/new "
        "mixed-row tests."
    ),
    (
        "## Production decision\n\nThe authoritative production values remain **one-MiB "
        "chunks** and a **256-KiB inline threshold**. The completed local, RTT, concurrency, "
        "full and 512-MiB capacity evidence does not justify a global, extension-specific or "
        "payload-class adaptive persisted layout. No migration is introduced, and legacy "
        "repositories remain readable without rewrite.\n\nA future format change requires new "
        "evidence that passes the same complete PostgreSQL and SQL Server write, sequential, "
        "short-read and random-read contract. Such a change must then introduce the additive "
        "versioned layout metadata above together with legacy/new mixed-row, restart, rollback "
        "and corruption tests."
    ),
    "production decision",
)
pack_path.write_text(pack, encoding="utf-8")


status_path = Path("docs/performance-status.md")
status = status_path.read_text(encoding="utf-8")
status = replace_once(
    status,
    (
        "- read-ahead remains access-pattern-aware instead of forcing one global window;\n"
        "- repository maintenance is not enabled automatically:"
    ),
    (
        "- read-ahead remains access-pattern-aware instead of forcing one global window;\n"
        "- the complete 512-MiB PostgreSQL/SQL Server matrix retains one-MiB chunks and a "
        "256-KiB inline threshold: larger chunks improve sequential work but regress sparse "
        "reads by 37–171%, while 256-KiB chunks regress writes and sequential reads;\n"
        "- repository maintenance is not enabled automatically:"
    ),
    "executive pack-layout bullet",
)
status = replace_once(
    status,
    (
        "| Pack chunk window | Default 16, configurable 1–64 | One-MiB chunks make the "
        "peak retained payload per active writer explicit and bounded. |\n"
        "| Pack chunk writer |"
    ),
    (
        "| Pack chunk window | Default 16, configurable 1–64 | One-MiB chunks make the "
        "peak retained payload per active writer explicit and bounded. |\n"
        "| Persisted pack layout | One-MiB chunks; 256-KiB inline threshold | Complete "
        "512-MiB PostgreSQL and SQL Server evidence rejects 256-KiB, two-MiB and four-MiB "
        "alternatives: each regresses either write/sequential or sparse access beyond the "
        "promotion budget. |\n"
        "| Pack chunk writer |"
    ),
    "production decision table row",
)
status_path.write_text(status, encoding="utf-8")
