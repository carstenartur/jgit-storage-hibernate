#!/usr/bin/env python3
import json
import sys
from pathlib import Path

Path("docs/badges").mkdir(parents=True, exist_ok=True)
data = json.loads(Path(sys.argv[1]).read_text())
if data:
    metric = data[0]["primaryMetric"]
    score = float(metric["score"])
    unit = metric.get("scoreUnit", "ms/op")
    color = "brightgreen" if score <= 5 else "yellow" if score <= 20 else "orange" if score <= 50 else "red"
    message = f"{score:.2f} {unit}"
else:
    color = "lightgrey"
    message = "unknown"
Path("docs/badges/performance.json").write_text(json.dumps({"schemaVersion": 1, "label": "JMH", "message": message, "color": color}, indent=2) + "\n")
