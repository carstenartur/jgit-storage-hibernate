#!/usr/bin/env python3
"""Add core migration 0.9.2 to the exact Flyway contract."""

from pathlib import Path

path = Path(
    "jgit-storage-hibernate-core/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/"
    "CoreSchemaMigrationIntegrationTest.java"
)
text = path.read_text(encoding="utf-8")
old = '''          "0.1.18",
          "0.9.1");'''
new = '''          "0.1.18",
          "0.9.1",
          "0.9.2");'''
if text.count(old) != 1:
    raise SystemExit(
        f"Expected one core migration-list anchor, found {text.count(old)}"
    )
path.write_text(text.replace(old, new), encoding="utf-8")
