#!/usr/bin/env python3
"""Distinguish incomplete evidence from a completed no-net-benefit decision."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


converter_path = Path(".github/scripts/convert-jmh-pack-storage-layout.py")
converter = converter_path.read_text(encoding="utf-8")
converter = replace_once(
    converter,
    '''    candidates = _layout_candidates(evidence)
    backends = sorted({row["backend"] for row in evidence})
    cross_database = "postgresql" in backends and "sqlserver" in backends
    eligible = [candidate for candidate in candidates if candidate["eligible"]]
    decision = (
        "candidate-layout-ready-for-versioned-format-design"
        if cross_database and eligible
        else "retain-current-layout-pending-postgresql-and-sqlserver-evidence"
    )
    return {
        "schemaVersion": 1,
        "decision": decision,
''',
    '''    candidates = _layout_candidates(evidence)
    backends = sorted({row["backend"] for row in evidence})
    required_backends = {"postgresql", "sqlserver"}
    cross_database = required_backends.issubset(backends)
    cross_database_evidence_complete = (
        cross_database
        and bool(candidates)
        and all(
            required_backends.issubset(
                summary["backend"] for summary in candidate["backendEvidence"]
            )
            and all(
                summary["writeMedianImprovementPercent"] is not None
                and summary["sequentialMedianImprovementPercent"] is not None
                and not summary["missingSparseOperations"]
                for summary in candidate["backendEvidence"]
                if summary["backend"] in required_backends
            )
            for candidate in candidates
        )
    )
    eligible = [candidate for candidate in candidates if candidate["eligible"]]
    if cross_database_evidence_complete and eligible:
        decision = "candidate-layout-ready-for-versioned-format-design"
    elif cross_database_evidence_complete:
        decision = "retain-current-layout-no-cross-database-net-benefit"
    else:
        decision = "retain-current-layout-pending-postgresql-and-sqlserver-evidence"
    return {
        "schemaVersion": 1,
        "decision": decision,
        "crossDatabaseEvidenceComplete": cross_database_evidence_complete,
''',
    "converter decision block",
)
converter_path.write_text(converter, encoding="utf-8")


test_path = Path(".github/scripts/test_convert_jmh_pack_storage_layout.py")
tests = test_path.read_text(encoding="utf-8")
tests = replace_once(
    tests,
    '''        self.assertFalse(report["productionDefaultsChanged"])
        self.assertTrue(report["compatibility"]["legacyRowsRemainOneMiB"])

    def test_cross_database_net_gain_can_only_propose_a_versioned_candidate(
''',
    '''        self.assertFalse(report["crossDatabaseEvidenceComplete"])
        self.assertFalse(report["productionDefaultsChanged"])
        self.assertTrue(report["compatibility"]["legacyRowsRemainOneMiB"])

    def test_cross_database_net_gain_can_only_propose_a_versioned_candidate(
''',
    "single-backend completeness assertion",
)
tests = replace_once(
    tests,
    '''        self.assertTrue(candidate["eligible"])
        self.assertFalse(report["productionDefaultsChanged"])

    def test_sparse_read_regression_rejects_a_write_optimized_candidate(
''',
    '''        self.assertTrue(candidate["eligible"])
        self.assertTrue(report["crossDatabaseEvidenceComplete"])
        self.assertFalse(report["productionDefaultsChanged"])

    def test_sparse_read_regression_rejects_a_write_optimized_candidate(
''',
    "eligible completeness assertion",
)
tests = replace_once(
    tests,
    '''        self.assertFalse(candidate["eligible"])
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_missing_sparse_evidence_cannot_promote_a_candidate(self) -> None:
''',
    '''        self.assertFalse(candidate["eligible"])
        self.assertTrue(report["crossDatabaseEvidenceComplete"])
        self.assertEqual(
            "retain-current-layout-no-cross-database-net-benefit",
            report["decision"],
        )

    def test_missing_sparse_evidence_cannot_promote_a_candidate(self) -> None:
''',
    "complete rejected decision assertion",
)
tests = replace_once(
    tests,
    '''        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_incomplete_sparse_evidence_cannot_promote_a_candidate(self) -> None:
''',
    '''        self.assertFalse(report["crossDatabaseEvidenceComplete"])
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_incomplete_sparse_evidence_cannot_promote_a_candidate(self) -> None:
''',
    "missing sparse completeness assertion",
)
tests = replace_once(
    tests,
    '''        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    '''        self.assertFalse(report["crossDatabaseEvidenceComplete"])
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    "partial sparse completeness assertion",
)
test_path.write_text(tests, encoding="utf-8")


pack_path = Path("docs/operations/pack-storage-layout.md")
pack = pack_path.read_text(encoding="utf-8")
pack = replace_once(
    pack,
    '''A write-only improvement is insufficient. A candidate is eligible for later format design only when both PostgreSQL and SQL Server contain write, sequential-read, short-read and random-read comparisons, show write and sequential-read gains, and both sparse access patterns regress by no more than five percent. Until that cross-database condition is met, the generated decision remains:

```text
retain-current-layout-pending-postgresql-and-sqlserver-evidence
```
''',
    '''A write-only improvement is insufficient. A candidate is eligible for later format design only when both PostgreSQL and SQL Server contain write, sequential-read, short-read and random-read comparisons, show write and sequential-read gains, and both sparse access patterns regress by no more than five percent. Machine-readable evidence distinguishes three states:

```text
retain-current-layout-pending-postgresql-and-sqlserver-evidence
retain-current-layout-no-cross-database-net-benefit
candidate-layout-ready-for-versioned-format-design
```

The pending state is reserved for missing backends or required operations. A complete matrix with no eligible candidate records the no-net-benefit decision explicitly.
''',
    "machine decision contract",
)
pack = replace_once(
    pack,
    (
        "The machine decision remains `retain-current-layout-pending-postgresql-and-sqlserver-evidence`; "
        "in this completed matrix that label means that no candidate passed, not that a production-database "
        "run is still missing. The authoritative operational conclusion is therefore to retain the current layout."
    ),
    (
        "The final machine decision is `retain-current-layout-no-cross-database-net-benefit`. "
        "The pending state is no longer overloaded for a completed rejection: it is emitted only "
        "when a production backend or required access pattern is missing."
    ),
    "final decision label explanation",
)
pack_path.write_text(pack, encoding="utf-8")
