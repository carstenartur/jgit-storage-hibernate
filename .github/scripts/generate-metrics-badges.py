#!/usr/bin/env python3
"""Generate Shields endpoint JSON files for tests and JaCoCo coverage."""

from __future__ import annotations

import json
from pathlib import Path
import xml.etree.ElementTree as ET

BADGE_DIR = Path("docs/badges")


def color_for_percent(value: float) -> str:
    if value >= 80:
        return "brightgreen"
    if value >= 60:
        return "yellow"
    if value >= 40:
        return "orange"
    return "red"


def write_badge(name: str, label: str, message: str, color: str) -> None:
    BADGE_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "label": label,
        "message": message,
        "color": color,
    }
    (BADGE_DIR / f"{name}.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def tests() -> tuple[int, int, int]:
    total = 0
    failures = 0
    skipped = 0
    for path in Path(".").glob("**/target/surefire-reports/TEST-*.xml"):
        root = ET.parse(path).getroot()
        total += int(root.attrib.get("tests", "0"))
        failures += int(root.attrib.get("failures", "0")) + int(root.attrib.get("errors", "0"))
        skipped += int(root.attrib.get("skipped", "0"))
    return total, failures, skipped


def coverage() -> float | None:
    covered = 0
    missed = 0
    for path in Path(".").glob("**/target/site/jacoco/jacoco.xml"):
        root = ET.parse(path).getroot()
        for counter in root.iter("counter"):
            if counter.attrib.get("type") == "INSTRUCTION":
                covered += int(counter.attrib.get("covered", "0"))
                missed += int(counter.attrib.get("missed", "0"))
    total = covered + missed
    if total == 0:
        return None
    return covered * 100.0 / total


def main() -> None:
    total, failures, skipped = tests()
    if failures:
        write_badge("tests", "tests", f"{failures} failing", "red")
    elif total:
        suffix = f", {skipped} skipped" if skipped else ""
        write_badge("tests", "tests", f"{total}{suffix}", "brightgreen")
    else:
        write_badge("tests", "tests", "unknown", "lightgrey")

    coverage_percent = coverage()
    if coverage_percent is None:
        write_badge("coverage", "coverage", "unknown", "lightgrey")
    else:
        write_badge(
            "coverage",
            "coverage",
            f"{coverage_percent:.1f}%",
            color_for_percent(coverage_percent),
        )


if __name__ == "__main__":
    main()
