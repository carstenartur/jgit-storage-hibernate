#!/usr/bin/env python3
"""Validate published benchmark suites against pinned consumer capability contracts."""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
from pathlib import Path
from typing import Any

DEFAULT_SUITE = "Repository backend comparison"
SUITE_ARGUMENT = re.compile(r"--suite-name\s+(?:'([^']+)'|\"([^\"]+)\"|([^\s\\]+))")
PUBLISHER_INVOCATION = re.compile(
    r"(?m)^\s*python3\s+\.github/scripts/publish-benchmark-history\.py\b"
)


def _load_publisher(script: Path) -> Any:
    spec = importlib.util.spec_from_file_location("publish_benchmark_history", script)
    if spec is None or spec.loader is None:
        raise ValueError(f"Cannot load benchmark publisher {script}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def published_suites(workflow_directory: Path) -> set[str]:
    suites: set[str] = set()
    for workflow in sorted(workflow_directory.glob("*.y*ml")):
        text = workflow.read_text(encoding="utf-8")
        invocation_count = len(PUBLISHER_INVOCATION.findall(text))
        if invocation_count == 0:
            continue
        explicit = {
            next(group for group in match.groups() if group is not None)
            for match in SUITE_ARGUMENT.finditer(text)
        }
        suites.update(explicit)
        if invocation_count > len(explicit):
            suites.add(DEFAULT_SUITE)
    if not suites:
        raise ValueError(f"No benchmark publisher invocation found in {workflow_directory}")
    return suites


def verify(root: Path) -> dict[str, Any]:
    scripts = root / ".github" / "scripts"
    publisher = _load_publisher(scripts / "publish-benchmark-history.py")
    catalog, rules = publisher.load_consumer_relevance_contract(
        root / ".github" / "consumer-compatibility.json",
        root / ".github" / "benchmark-consumer-relevance.json",
    )
    published = published_suites(root / ".github" / "workflows")
    mapped = set(rules)
    missing = sorted(published - mapped)
    unused = sorted(mapped - published)
    if missing or unused:
        details: list[str] = []
        if missing:
            details.append("published suites without mappings: " + ", ".join(missing))
        if unused:
            details.append("mappings without publisher invocations: " + ", ".join(unused))
        raise ValueError("; ".join(details))

    return {
        "schemaVersion": 1,
        "consumers": catalog,
        "suites": [
            {
                "suite": suite,
                "contract": rules[suite]["contract"],
                "requiredModules": rules[suite]["requiredModules"],
                "consumers": rules[suite]["consumers"],
            }
            for suite in sorted(rules)
        ],
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--evidence", type=Path)
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    try:
        evidence = verify(args.root.resolve())
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        raise SystemExit("Benchmark consumer relevance contract error: " + str(failure)) from failure
    rendered = json.dumps(evidence, indent=2, ensure_ascii=False) + "\n"
    if args.evidence is not None:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
