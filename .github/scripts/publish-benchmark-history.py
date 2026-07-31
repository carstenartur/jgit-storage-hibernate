#!/usr/bin/env python3
"""Update the GitHub Pages benchmark history without changing Git branches in CI."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from pathlib import Path
from typing import Any

from benchmark_units import normalize_benchmark

SCRIPT_PREFIX = "window.BENCHMARK_DATA = "


def _git(repository: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout.strip()


def _normalize_history(data: dict[str, Any]) -> dict[str, Any]:
    entries = data.get("entries")
    if not isinstance(entries, dict):
        raise ValueError("Benchmark data has no entries object")

    for suite_name, history in entries.items():
        if not isinstance(history, list):
            raise ValueError(f"Benchmark suite {suite_name!r} is not a list")
        for entry_index, entry in enumerate(history):
            if not isinstance(entry, dict):
                raise ValueError(
                    f"Benchmark suite {suite_name!r} entry {entry_index} is not an object"
                )
            benches = entry.get("benches")
            if not isinstance(benches, list):
                raise ValueError(
                    f"Benchmark suite {suite_name!r} entry {entry_index} has no benches array"
                )
            entry["benches"] = [normalize_benchmark(benchmark) for benchmark in benches]
    return data


def _load_data(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"lastUpdate": 0, "repoUrl": "", "entries": {}}

    content = path.read_text(encoding="utf-8")
    if not content.startswith(SCRIPT_PREFIX):
        raise ValueError(f"Benchmark data file {path} does not start with the expected prefix")
    parsed = json.loads(content[len(SCRIPT_PREFIX) :])
    if not isinstance(parsed, dict) or not isinstance(parsed.get("entries"), dict):
        raise ValueError(f"Benchmark data file {path} has an invalid structure")
    return _normalize_history(parsed)


def _load_benches(path: Path) -> list[dict[str, Any]]:
    parsed = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(parsed, list) or not parsed:
        raise ValueError(f"Benchmark result {path} must be a non-empty JSON array")

    benches: list[dict[str, Any]] = []
    for index, benchmark in enumerate(parsed):
        if not isinstance(benchmark, dict):
            raise ValueError(f"Benchmark entry {index} is not an object")
        for field in ("name", "unit", "value"):
            if field not in benchmark:
                raise ValueError(f"Benchmark entry {index} is missing {field!r}")
        if not isinstance(benchmark["name"], str) or not benchmark["name"]:
            raise ValueError(f"Benchmark entry {index} has an invalid name")
        if not isinstance(benchmark["unit"], str) or not benchmark["unit"]:
            raise ValueError(f"Benchmark entry {index} has an invalid unit")
        if not isinstance(benchmark["value"], (int, float)):
            raise ValueError(f"Benchmark entry {index} has a non-numeric value")
        benches.append(normalize_benchmark(benchmark))
    return benches


def _commit_metadata(repository: Path, commit: str, repository_url: str, actor: str) -> dict[str, Any]:
    author_name = _git(repository, "show", "-s", "--format=%an", commit)
    author_email = _git(repository, "show", "-s", "--format=%ae", commit)
    committer_name = _git(repository, "show", "-s", "--format=%cn", commit)
    committer_email = _git(repository, "show", "-s", "--format=%ce", commit)
    timestamp = _git(repository, "show", "-s", "--format=%cI", commit)
    tree_id = _git(repository, "show", "-s", "--format=%T", commit)
    message = _git(repository, "show", "-s", "--format=%B", commit)

    return {
        "author": {"email": author_email, "name": author_name, "username": actor},
        "committer": {"email": committer_email, "name": committer_name, "username": actor},
        "distinct": True,
        "id": commit,
        "message": message,
        "timestamp": timestamp,
        "tree_id": tree_id,
        "url": f"{repository_url.rstrip('/')}/commit/{commit}",
    }


def _ratio(previous: dict[str, Any], current: dict[str, Any], smaller_is_better: bool) -> float:
    previous_value = float(previous["value"])
    current_value = float(current["value"])
    if previous_value == 0.0 and current_value == 0.0:
        return 1.0
    if smaller_is_better:
        return float("inf") if previous_value == 0.0 else current_value / previous_value
    return float("inf") if current_value == 0.0 else previous_value / current_value


def _write_summary(
    path: Path,
    suite_name: str,
    previous: dict[str, Any] | None,
    current: dict[str, Any],
    alert_threshold: float,
) -> None:
    lines = [f"### {suite_name}", ""]
    if previous is None:
        lines.append("Stored the first comparable benchmark result.")
    else:
        previous_by_name = {bench["name"]: bench for bench in previous.get("benches", [])}
        lines.extend(
            [
                f"Current `{current['commit']['id'][:7]}` compared with `{previous['commit']['id'][:7]}`.",
                "",
                "| Benchmark | Current | Previous | Ratio |",
                "|---|---:|---:|---:|",
            ]
        )
        alerts: list[str] = []
        for benchmark in current["benches"]:
            old = previous_by_name.get(benchmark["name"])
            if old is None:
                lines.append(
                    f"| `{benchmark['name']}` | {benchmark['value']} {benchmark['unit']} | — | — |"
                )
                continue
            ratio = _ratio(old, benchmark, smaller_is_better=True)
            ratio_text = "∞" if ratio == float("inf") else f"{ratio:.2f}×"
            lines.append(
                f"| `{benchmark['name']}` | {benchmark['value']} {benchmark['unit']} | "
                f"{old['value']} {old['unit']} | {ratio_text} |"
            )
            if ratio > alert_threshold:
                alerts.append(benchmark["name"])
        if alerts:
            lines.extend(
                [
                    "",
                    f"⚠️ {len(alerts)} result(s) exceeded the {alert_threshold:.2f}× alert threshold: "
                    + ", ".join(f"`{name}`" for name in alerts),
                ]
            )
        else:
            lines.extend(["", "No benchmark exceeded the configured regression alert threshold."])

    with path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines) + "\n")


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
    if max_items < 1:
        raise ValueError("max-items must be at least one")
    if alert_threshold <= 0:
        raise ValueError("alert-threshold must be positive")

    data = _load_data(data_file)
    benches = _load_benches(benchmark_file)
    suites = data.setdefault("entries", {})
    history = suites.setdefault(suite_name, [])
    if not isinstance(history, list):
        raise ValueError(f"Benchmark suite {suite_name!r} is not a list")

    previous = next(
        (entry for entry in reversed(history) if entry.get("commit", {}).get("id") != commit),
        None,
    )
    current = {
        "commit": _commit_metadata(repository_dir, commit, repository_url, actor),
        "date": timestamp_ms,
        "tool": tool,
        "benches": benches,
    }

    history[:] = [entry for entry in history if entry.get("commit", {}).get("id") != commit]
    history.append(current)
    if len(history) > max_items:
        del history[:-max_items]

    data["lastUpdate"] = timestamp_ms
    data["repoUrl"] = repository_url.rstrip("/")
    data_file.parent.mkdir(parents=True, exist_ok=True)
    data_file.write_text(
        SCRIPT_PREFIX + json.dumps(data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    if summary_file is not None:
        _write_summary(summary_file, suite_name, previous, current, alert_threshold)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-file", required=True, type=Path)
    parser.add_argument("--benchmark-file", required=True, type=Path)
    parser.add_argument("--repository-dir", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--actor", default="github-actions[bot]")
    parser.add_argument("--suite-name", default="Repository backend comparison")
    parser.add_argument("--tool", default="customSmallerIsBetter")
    parser.add_argument("--max-items", type=int, default=100)
    parser.add_argument("--timestamp-ms", type=int, default=None)
    parser.add_argument("--summary-file", type=Path)
    parser.add_argument("--alert-threshold", type=float, default=1.5)
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
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
