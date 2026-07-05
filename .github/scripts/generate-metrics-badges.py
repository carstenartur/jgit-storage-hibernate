#!/usr/bin/env python3
import json
from pathlib import Path
import xml.etree.ElementTree as ET

Path("docs/badges").mkdir(parents=True, exist_ok=True)

def badge(name, label, message, color):
    Path(f"docs/badges/{name}.json").write_text(json.dumps({
        "schemaVersion": 1,
        "label": label,
        "message": message,
        "color": color,
    }, indent=2) + "\n")

tests = failures = skipped = 0
for path in Path(".").glob("**/target/surefire-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    tests += int(root.attrib.get("tests", "0"))
    failures += int(root.attrib.get("failures", "0")) + int(root.attrib.get("errors", "0"))
    skipped += int(root.attrib.get("skipped", "0"))

if failures:
    badge("tests", "tests", f"{failures} failing", "red")
elif tests:
    badge("tests", "tests", str(tests), "brightgreen")
else:
    badge("tests", "tests", "unknown", "lightgrey")

covered = missed = 0
for path in Path(".").glob("**/target/site/jacoco/jacoco.xml"):
    root = ET.parse(path).getroot()
    for counter in root.iter("counter"):
        if counter.attrib.get("type") == "INSTRUCTION":
            covered += int(counter.attrib.get("covered", "0"))
            missed += int(counter.attrib.get("missed", "0"))

total = covered + missed
if total:
    value = covered * 100.0 / total
    color = "brightgreen" if value >= 80 else "yellow" if value >= 60 else "orange" if value >= 40 else "red"
    badge("coverage", "coverage", f"{value:.1f}%", color)
else:
    badge("coverage", "coverage", "unknown", "lightgrey")
