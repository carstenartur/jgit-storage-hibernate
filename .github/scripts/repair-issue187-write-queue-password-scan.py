#!/usr/bin/env python3
"""Keep the generated write-queue scanner focused on attributable leak markers."""

from pathlib import Path

path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PerformanceInvestigationsBenchmarkIT.java"
)
text = path.read_text(encoding="utf-8")
old = '''            "Database JDBC URL",
            "Default catalog/schema",
            POSTGRESQL.getPassword());
'''
new = '''            "Database JDBC URL",
            "Default catalog/schema");
'''
if text.count(old) != 1:
    raise SystemExit(
        f"Expected one generated password-token scan block, found {text.count(old)}"
    )
path.write_text(text.replace(old, new), encoding="utf-8")
