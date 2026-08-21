#!/usr/bin/env python3
"""Require real alternative layouts before finalizing cross-database evidence."""

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
    required_backends = {"postgresql", "sqlserver"}
    cross_database = required_backends.issubset(backends)
    cross_database_evidence_complete = (
        cross_database
        and bool(candidates)
''',
    '''    candidates = _layout_candidates(evidence)
    alternative_candidates = [
        candidate
        for candidate in candidates
        if candidate["chunkKiB"] != CURRENT_CHUNK_KIB
        or candidate["inlineKiB"] != CURRENT_INLINE_KIB
    ]
    backends = sorted({row["backend"] for row in evidence})
    required_backends = {"postgresql", "sqlserver"}
    cross_database = required_backends.issubset(backends)
    cross_database_evidence_complete = (
        cross_database
        and bool(alternative_candidates)
''',
    "alternative-candidate completeness guard",
)
converter_path.write_text(converter, encoding="utf-8")


test_path = Path(".github/scripts/test_convert_jmh_pack_storage_layout.py")
tests = test_path.read_text(encoding="utf-8")
tests = replace_once(
    tests,
    '''    def test_cross_database_net_gain_can_only_propose_a_versioned_candidate(
        self,
    ) -> None:
''',
    '''    def test_cross_database_baseline_only_evidence_is_pending(self) -> None:
        results = [
            result
            for result in self.matrix(
                ["postgresql", "sqlserver"],
                sparse_candidate=9.7,
            )
            if result["params"]["chunkKiB"] == "1024"
        ]
        report = CONVERTER.convert(results)
        self.assertFalse(report["crossDatabaseEvidenceComplete"])
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )
        self.assertEqual(
            [(1024, 256)],
            [
                (candidate["chunkKiB"], candidate["inlineKiB"])
                for candidate in report["layoutCandidates"]
            ],
        )

    def test_cross_database_net_gain_can_only_propose_a_versioned_candidate(
        self,
    ) -> None:
''',
    "baseline-only regression",
)
test_path.write_text(tests, encoding="utf-8")


pack_path = Path("docs/operations/pack-storage-layout.md")
pack = pack_path.read_text(encoding="utf-8")
pack = replace_once(
    pack,
    (
        "The pending state is reserved for missing backends or required operations. "
        "A complete matrix with no eligible candidate records the no-net-benefit "
        "decision explicitly."
    ),
    (
        "The pending state is reserved for missing backends, required operations or "
        "a baseline-only result without any alternative layout. A complete matrix with "
        "at least one real alternative and no eligible candidate records the "
        "no-net-benefit decision explicitly."
    ),
    "pending-state completeness explanation",
)
pack = replace_once(
    pack,
    (
        "The final machine decision is `retain-current-layout-no-cross-database-net-benefit`. "
        "The pending state is no longer overloaded for a completed rejection: it is emitted only "
        "when a production backend or required access pattern is missing."
    ),
    (
        "The final machine decision is `retain-current-layout-no-cross-database-net-benefit`. "
        "It is replayed from the combined retained full and capacity production-database "
        "measurements. The pending state is no longer overloaded for a completed rejection: "
        "it is emitted when a production backend, required access pattern or real alternative "
        "layout is missing."
    ),
    "final combined decision explanation",
)
pack_path.write_text(pack, encoding="utf-8")
