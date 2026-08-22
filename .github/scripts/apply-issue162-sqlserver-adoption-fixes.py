#!/usr/bin/env python3
"""Align SQL Server migration/adoption contracts with core schema 0.9.2."""

from pathlib import Path


def replace_once(path: Path, old: str, new: str, description: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {description} anchor in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


post_adoption = Path(
    "jgit-storage-hibernate-core/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/"
    "LegacyCoreSqlServerPostAdoptionWriteIntegrationTest.java"
)
replace_once(
    post_adoption,
    'assertEquals("0.9.1", flyway.info().current().getVersion().getVersion());',
    'assertEquals("0.9.2", flyway.info().current().getVersion().getVersion());',
    "post-adoption schema version",
)

legacy_adoption = Path(
    "jgit-storage-hibernate-core/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/"
    "LegacyCoreSqlServerSchemaAdoptionIntegrationTest.java"
)
replace_once(
    legacy_adoption,
    'assertEquals("0.9.1", flyway.info().current().getVersion().getVersion());',
    'assertEquals("0.9.2", flyway.info().current().getVersion().getVersion());',
    "legacy-adoption schema version",
)
replace_once(
    legacy_adoption,
    '''      statement.execute(
          "drop index if exists idx_reflog_repo_ref_key_id on git_reflog");
      statement.execute(
          "if col_length('git_reflog', 'ref_name_key') is not null "
              + "alter table git_reflog drop column ref_name_key");
''',
    '''      statement.execute(
          "drop index if exists idx_reflog_repo_delivery on git_reflog");
      statement.execute(
          "if col_length('git_reflog', 'delivery_id') is not null "
              + "alter table git_reflog drop column delivery_id");
      statement.execute(
          "drop index if exists idx_reflog_repo_ref_key_id on git_reflog");
      statement.execute(
          "if col_length('git_reflog', 'ref_name_key') is not null "
              + "alter table git_reflog drop column ref_name_key");
''',
    "legacy reflog downgrade",
)
