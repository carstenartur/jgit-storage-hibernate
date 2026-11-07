#!/usr/bin/env python3
"""Verify retained audio concurrency coordinates, hashes and raw counter invariants."""

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
    records = json.loads(gzip.decompress((root / "audio.json.gz").read_bytes()))
    expected = set(itertools.product(("audioPublish", "audioReadRecent"), ("stateful", "stateless"), (8, 16, 32), (1, 4)))
    found = set()
    publication_bytes = {}
    for record in records:
        params = record["params"]
        operation = record["benchmark"].rsplit(".", 1)[-1]
        batch, repositories = int(params["chunkBatch"]), int(params["repositories"])
        key = operation, params["writer"], batch, repositories
        assert key in expected and key not in found, key
        found.add(key)
        assert params["coordinate"] == "16777216" and params["pressure"] == "bounded"
        assert params["analysisMiB"] == "32" and params["analysisWorkers"] == "1"
        assert "-Xmx1g" in record["jvmArgs"]
        metrics = record["secondaryMetrics"]

        def samples(name):
            metric = record["primaryMetric"] if name == "time" else metrics[name]
            values = [item for fork in metric["rawData"] for item in fork]
            assert len(values) == 3 and all(math.isfinite(item) for item in values), (key, name)
            return values

        assert all(value > 0 for value in samples("time"))
        assert all(value > 0 for value in samples("gc.alloc.rate.norm"))
        assert all(value >= 0 for value in samples("gc.count"))
        assert samples("payloadBytes") == [16777216 * repositories] * 3
        assert samples("repositoriesProcessed") == [repositories] * 3
        assert samples("retainedAnalysisBytes") == [33554432] * 3
        assert samples("configuredRetainedChunkBytes") == [1048576 * batch * repositories] * 3
        for name in ("preparedStatements", "transactions", "databaseBytesRead"):
            assert all(value >= 0 for value in samples(name))
        if operation == "audioPublish":
            assert samples("jdbcBatches") == [math.ceil(17 / batch) * repositories] * 3
            written = samples("databaseBytesWritten")
            assert all(value > 16777216 * repositories for value in written)
            same_work = batch, repositories
            if same_work in publication_bytes:
                assert publication_bytes[same_work] == written, "Writer payload mismatch"
            publication_bytes[same_work] = written
        else:
            assert samples("jdbcBatches") == [0] * 3
            assert samples("databaseBytesWritten") == [0] * 3
    assert found == expected
    return {"coordinates": len(found), "observations": len(found) * 3}


if __name__ == "__main__":
    print(json.dumps(verify(Path(sys.argv[1])), sort_keys=True))
