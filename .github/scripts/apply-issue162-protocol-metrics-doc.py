#!/usr/bin/env python3
"""Document the new atomic reflog batch transaction category."""

from pathlib import Path

path = Path("docs/protocol-storage-metrics.md")
text = path.read_text(encoding="utf-8")
old_table = '''| `REFLOG_READ` | standalone reflog retrieval |
| `REFLOG_WRITE` | standalone reflog persistence outside a ref-publication transaction |
| `OTHER` | explicit uncategorized application work or an internal call site still requiring classification |
'''
new_table = '''| `REFLOG_READ` | standalone reflog retrieval |
| `REFLOG_WRITE` | standalone reflog persistence outside a ref-publication transaction |
| `REFLOG_BATCH_WRITE` | repository-locked atomic JDBC batch of idempotent queryable reflog projection records |
| `OTHER` | explicit uncategorized application work or an internal call site still requiring classification |
'''
if text.count(old_table) != 1:
    raise SystemExit(f"Expected one operation-kind table anchor, found {text.count(old_table)}")
text = text.replace(old_table, new_table)

old_interpretation = '''- many `REF_PUBLICATION` transactions or locks suggest ref/reftable coordination is the dominant fixed cost;
- many `PACK_METADATA_READ` transactions suggest pack-list reconstruction should be examined;
'''
new_interpretation = '''- many `REF_PUBLICATION` transactions or locks suggest ref/reftable coordination is the dominant fixed cost;
- `REFLOG_BATCH_WRITE` isolates the first Git-aware append-only projection batch; interpret it with delivery-ID replay, queue batch-size and database-native WAL/log evidence rather than treating it as authoritative ref publication;
- many `PACK_METADATA_READ` transactions suggest pack-list reconstruction should be examined;
'''
if text.count(old_interpretation) != 1:
    raise SystemExit(
        f"Expected one interpretation-list anchor, found {text.count(old_interpretation)}"
    )
path.write_text(text.replace(old_interpretation, new_interpretation), encoding="utf-8")
