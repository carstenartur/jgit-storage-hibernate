#!/usr/bin/env python3
"""Verify raw, exact-source sandbox projection measurements and structural counters."""

import gzip
import hashlib
import itertools
import json
import math
from pathlib import Path
import sys


def verify(root):
    manifest = json.loads((root / "manifest.json").read_text())
    assert manifest["schemaVersion"] == 1
    for files in (manifest["raw"], manifest["harness"]):
        for name, expected in files.items():
            assert hashlib.sha256(gzip.decompress((root / name).read_bytes())).hexdigest() == expected, name
    records = json.loads(gzip.decompress((root / "sandbox.json.gz").read_bytes()))
    expected = set(itertools.product(
        ("sandboxPersistBodyEdit", "sandboxRebuildSemanticIndex", "sandboxPathSymbols", "sandboxMethodReferences"),
        (250, 1000)))
    found = set()
    for record in records:
        operation = record["benchmark"].rsplit(".", 1)[-1]
        classes = int(record["params"]["coordinate"])
        key = operation, classes
        assert key in expected and key not in found, key
        found.add(key)
        assert "-Xmx1g" in record["jvmArgs"]

        def samples(name):
            metric = record["primaryMetric"] if name == "time" else record["secondaryMetrics"][name]
            values = [item for fork in metric["rawData"] for item in fork]
            assert len(values) == 3 and all(math.isfinite(item) for item in values), (key, name)
            return values

        assert all(value > 0 for value in samples("time"))
        assert all(value > 0 for value in samples("gc.alloc.rate.norm"))
        assert all(value >= 0 for value in samples("gc.count"))
        assert all(value > 0 for value in samples("indexBytes"))
        for name in ("preparedStatements", "jdbcBatches"):
            assert all(value >= 0 for value in samples(name))
        if operation == "sandboxPersistBodyEdit":
            files, hits = 1, 2
        elif operation == "sandboxRebuildSemanticIndex":
            files, hits = classes + 1, 2 * classes + 3
        else:
            files, hits = 0, 2 if operation == "sandboxPathSymbols" else 1
        assert samples("javaFilesAnalyzed") == [files] * 3
        assert samples("transactions") == [files] * 3
        assert samples("resultCount") == [hits] * 3
    assert found == expected
    return {"coordinates": len(found), "observations": len(found) * 3}


if __name__ == "__main__":
    print(json.dumps(verify(Path(sys.argv[1])), sort_keys=True))
