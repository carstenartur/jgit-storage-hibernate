#!/usr/bin/env python3
"""Publish benchmark history and attach real-consumer relevance evidence."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from benchmark_consumer_relevance import annotate_file, load_contracts, postprocess_history


def _load_core() -> Any:
    path = SCRIPT_DIR / "publish-benchmark-history-core.py"
    spec = importlib.util.spec_from_file_location("publish_benchmark_history_core", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CORE = _load_core()
SCRIPT_PREFIX = CORE.SCRIPT_PREFIX


def load_consumer_relevance_contract(
    consumer_descriptor: Path, relevance_descriptor: Path
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    root = consumer_descriptor.resolve().parent.parent
    return load_contracts(root, consumer_descriptor.resolve(), relevance_descriptor.resolve())


def update_history(
    *,
    data_file: Path,
    benchmark_file: Path,
    repository_dir: Path,
    repository_url: str,
    commit: str,
    actor: str,
    suite_name: str,
    tool: str,
    max_items: int,
    timestamp_ms: int,
    summary_file: Path | None,
    alert_threshold: float,
) -> None:
    with tempfile.TemporaryDirectory(prefix="benchmark-consumer-relevance-") as temporary:
        annotated = Path(temporary) / "benchmarks.json"
        catalog, rules = annotate_file(benchmark_file, annotated, suite_name, repository_dir)
        CORE.update_history(
            data_file=data_file,
            benchmark_file=annotated,
            repository_dir=repository_dir,
            repository_url=repository_url,
            commit=commit,
            actor=actor,
            suite_name=suite_name,
            tool=tool,
            max_items=max_items,
            timestamp_ms=timestamp_ms,
            summary_file=summary_file,
            alert_threshold=alert_threshold,
        )
        postprocess_history(data_file, suite_name, commit, catalog, rules)
        if summary_file is not None and rules:
            rule = rules[suite_name]
            with summary_file.open("a", encoding="utf-8") as summary:
                summary.write(
                    "\nConsumer relevance (library contract evidence, not an application benchmark): "
                    + ", ".join(f"`{consumer}`" for consumer in rule["consumers"])
                    + "\nContract: `"
                    + str(rule["contract"])
                    + "`; required modules: "
                    + ", ".join(f"`{module}`" for module in rule["requiredModules"])
                    + "\n"
                )


def main() -> None:
    args = CORE._parse_args()
    update_history(
        data_file=args.data_file,
        benchmark_file=args.benchmark_file,
        repository_dir=args.repository_dir,
        repository_url=args.repository_url,
        commit=args.commit,
        actor=args.actor,
        suite_name=args.suite_name,
        tool=args.tool,
        max_items=args.max_items,
        timestamp_ms=args.timestamp_ms if args.timestamp_ms is not None else int(time.time() * 1000),
        summary_file=args.summary_file,
        alert_threshold=args.alert_threshold,
    )


if __name__ == "__main__":
    main()
