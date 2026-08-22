#!/usr/bin/env python3
"""Finish the exact Flyway and delivery-row contracts for issue #162."""

from pathlib import Path


def replace_once(path: Path, old: str, new: str, description: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {description} anchor, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


migration_test = Path(
    "jgit-storage-hibernate-core/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/"
    "CoreSchemaMigrationIntegrationTest.java"
)
replace_once(
    migration_test,
    '''          "0.1.18",
          "0.9.1");''',
    '''          "0.1.18",
          "0.9.1",
          "0.9.2");''',
    "core migration-list",
)

h2_contract = Path(
    "jgit-storage-hibernate-core/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/refs/"
    "HibernateReflogBatchProcessorH2Test.java"
)
replace_once(
    h2_contract,
    '"FROM GitReflogEntity r WHERE r.repositoryName = :repo ORDER BY r.id",',
    '"FROM GitReflogEntity r WHERE r.repositoryName = :repo "\n'
    '                    + "AND r.deliveryId IS NOT NULL ORDER BY r.id",',
    "delivery-row H2 query",
)

native_contract = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "ReflogBatchNativeTelemetryTest.java"
)
replace_once(
    native_contract,
    '"SELECT COUNT(r) FROM GitReflogEntity r WHERE r.repositoryName = :repo",',
    '"SELECT COUNT(r) FROM GitReflogEntity r WHERE r.repositoryName = :repo "\n'
    '                  + "AND r.deliveryId IS NOT NULL",',
    "delivery-row native query",
)

workflow = Path(".github/workflows/git-aware-reflog-batch.yml")
replace_once(
    workflow,
    '''      - name: Upload native batch evidence
        if: always()
''',
    '''      - name: Upload native batch evidence
        if: success()
''',
    "native artifact condition",
)
