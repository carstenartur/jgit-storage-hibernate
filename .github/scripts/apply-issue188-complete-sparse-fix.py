#!/usr/bin/env python3
"""Require complete short- and random-read evidence for issue #188."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one replacement in {path}, found {count}")
    target.write_text(text.replace(old, new), encoding="utf-8")


replace_once(
    ".github/scripts/convert-jmh-pack-storage-layout.py",
    '''            sparse = [
                value["relativeToCurrentPercent"]
                for value in backend_values
                if value["operation"] in SPARSE_OPERATIONS
            ]
''',
    '''            sparse_by_operation = {
                operation: [
                    value["relativeToCurrentPercent"]
                    for value in backend_values
                    if value["operation"] == operation
                ]
                for operation in SPARSE_OPERATIONS
            }
            missing_sparse_operations = sorted(
                operation
                for operation, comparisons in sparse_by_operation.items()
                if not comparisons
            )
            sparse = [
                comparison
                for comparisons in sparse_by_operation.values()
                for comparison in comparisons
            ]
''',
)

replace_once(
    ".github/scripts/convert-jmh-pack-storage-layout.py",
    '''                    "worstSparseImprovementPercent": (
                        min(sparse) if sparse else None
                    ),
                    "comparisonCount": len(backend_values),
''',
    '''                    "worstSparseImprovementPercent": (
                        min(sparse) if not missing_sparse_operations else None
                    ),
                    "missingSparseOperations": missing_sparse_operations,
                    "comparisonCount": len(backend_values),
''',
)

replace_once(
    ".github/scripts/test_convert_jmh_pack_storage_layout.py",
    '''        self.assertEqual(6, len(report["evidence"]))
''',
    '''        self.assertEqual(8, len(report["evidence"]))
''',
)

replace_once(
    ".github/scripts/test_convert_jmh_pack_storage_layout.py",
    '''        self.assertTrue(
            all(
                evidence["worstSparseImprovementPercent"] is None
                for evidence in candidate["backendEvidence"]
            )
        )
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    '''        self.assertTrue(
            all(
                evidence["worstSparseImprovementPercent"] is None
                for evidence in candidate["backendEvidence"]
            )
        )
        self.assertTrue(
            all(
                set(evidence["missingSparseOperations"])
                == CONVERTER.SPARSE_OPERATIONS
                for evidence in candidate["backendEvidence"]
            )
        )
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_incomplete_sparse_evidence_cannot_promote_a_candidate(self) -> None:
        results = [
            result
            for result in self.matrix(
                ["postgresql", "sqlserver"],
                sparse_candidate=9.7,
            )
            if result["params"]["operation"] != "short-read"
        ]
        report = CONVERTER.convert(results)
        candidate = next(
            item
            for item in report["layoutCandidates"]
            if item["chunkKiB"] == 2048 and item["inlineKiB"] == 256
        )
        self.assertFalse(candidate["eligible"])
        self.assertTrue(
            all(
                evidence["worstSparseImprovementPercent"] is None
                and evidence["missingSparseOperations"] == ["short-read"]
                for evidence in candidate["backendEvidence"]
            )
        )
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
)

replace_once(
    ".github/scripts/test_convert_jmh_pack_storage_layout.py",
    '''            for operation, baseline, candidate in (
                ("write", 10.0, 8.0),
                ("sequential-read", 10.0, 8.5),
                ("random-read", 10.0, sparse_candidate),
            ):
''',
    '''            for operation, baseline, candidate in (
                ("write", 10.0, 8.0),
                ("sequential-read", 10.0, 8.5),
                ("short-read", 10.0, sparse_candidate),
                ("random-read", 10.0, sparse_candidate),
            ):
''',
)

replace_once(
    "docs/operations/pack-storage-layout.md",
    '''A write-only improvement is insufficient. A candidate is eligible for later format design only when both PostgreSQL and SQL Server contain write, sequential-read and sparse-read comparisons, show write and sequential-read gains, and sparse reads regress by no more than five percent.''',
    '''A write-only improvement is insufficient. A candidate is eligible for later format design only when both PostgreSQL and SQL Server contain write, sequential-read, short-read and random-read comparisons, show write and sequential-read gains, and both sparse access patterns regress by no more than five percent.''',
)
