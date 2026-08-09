#!/usr/bin/env python3
"""Derive benchmark relevance from immutable real-consumer module contracts."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

SCRIPT_PREFIX = "window.BENCHMARK_DATA = "
CONSUMER_DESCRIPTOR = Path(".github/consumer-compatibility.json")
RELEVANCE_DESCRIPTOR = Path(".github/benchmark-consumer-relevance.json")


def _non_blank(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value.strip()


def _strings(value: object, field: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{field} must be a non-empty list")
    result = [_non_blank(item, field) for item in value]
    if len(result) != len(set(result)):
        raise ValueError(f"{field} must not contain duplicates")
    return sorted(result)


def load_contracts(
    root: Path,
    consumer_descriptor: Path | None = None,
    relevance_descriptor: Path | None = None,
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    consumer_path = consumer_descriptor or root / CONSUMER_DESCRIPTOR
    relevance_path = relevance_descriptor or root / RELEVANCE_DESCRIPTOR
    consumer_exists = consumer_path.is_file()
    relevance_exists = relevance_path.is_file()
    if not consumer_exists and not relevance_exists:
        return {}, {}
    if not consumer_exists or not relevance_exists:
        raise ValueError(
            "Consumer relevance requires both descriptors: "
            f"consumer={consumer_path} ({consumer_exists}), "
            f"relevance={relevance_path} ({relevance_exists})"
        )

    consumers_raw = json.loads(consumer_path.read_text(encoding="utf-8"))
    if not isinstance(consumers_raw, dict) or consumers_raw.get("schemaVersion") != 2:
        raise ValueError(f"Consumer descriptor {consumer_path} must use schemaVersion 2")
    consumer_entries = consumers_raw.get("consumers")
    if not isinstance(consumer_entries, list) or not consumer_entries:
        raise ValueError(f"Consumer descriptor {consumer_path} must declare consumers")

    catalog: dict[str, dict[str, Any]] = {}
    for index, raw in enumerate(consumer_entries):
        if not isinstance(raw, dict):
            raise ValueError(f"consumers[{index}] must be an object")
        consumer_id = _non_blank(raw.get("id"), f"consumers[{index}].id")
        if consumer_id in catalog:
            raise ValueError(f"Duplicate consumer id {consumer_id!r}")
        catalog[consumer_id] = {
            "displayName": _non_blank(
                raw.get("displayName", consumer_id), f"{consumer_id}.displayName"
            ),
            "repository": _non_blank(raw.get("repository"), f"{consumer_id}.repository"),
            "ref": _non_blank(raw.get("ref"), f"{consumer_id}.ref"),
            "defaultBranch": _non_blank(
                raw.get("defaultBranch"), f"{consumer_id}.defaultBranch"
            ),
            "modules": _strings(raw.get("expectedModules"), f"{consumer_id}.expectedModules"),
            "contractScript": _non_blank(
                raw.get("contractScript"), f"{consumer_id}.contractScript"
            ),
        }

    relevance_raw = json.loads(relevance_path.read_text(encoding="utf-8"))
    if not isinstance(relevance_raw, dict) or relevance_raw.get("schemaVersion") != 1:
        raise ValueError(f"Benchmark relevance descriptor {relevance_path} must use schemaVersion 1")
    suites = relevance_raw.get("suites")
    if not isinstance(suites, dict) or not suites:
        raise ValueError(f"Benchmark relevance descriptor {relevance_path} must declare suites")

    rules: dict[str, dict[str, Any]] = {}
    for raw_name, raw in suites.items():
        name = _non_blank(raw_name, "suite name")
        if not isinstance(raw, dict):
            raise ValueError(f"Benchmark relevance rule for {name!r} must be an object")
        required = _strings(raw.get("requiredModules"), f"{name}.requiredModules")
        matching = sorted(
            consumer_id
            for consumer_id, consumer in catalog.items()
            if set(required).issubset(set(consumer["modules"]))
        )
        if not matching:
            raise ValueError(
                f"Benchmark relevance rule {name!r} requires {required}, "
                "but no pinned consumer selects every required module"
            )
        rules[name] = {
            "contract": _non_blank(raw.get("contract"), f"{name}.contract"),
            "requiredModules": required,
            "consumers": matching,
        }
    return dict(sorted(catalog.items())), dict(sorted(rules.items()))


def annotate_benchmarks(
    benchmarks: list[dict[str, Any]],
    suite_name: str,
    catalog: dict[str, dict[str, Any]],
    rules: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    if not rules:
        return {}
    rule = rules.get(suite_name)
    if rule is None:
        raise ValueError(f"Benchmark suite {suite_name!r} has no consumer relevance rule")
    evidence = {consumer_id: dict(catalog[consumer_id]) for consumer_id in rule["consumers"]}
    derived = {
        "consumers": list(rule["consumers"]),
        "contract": str(rule["contract"]),
        "requiredModules": list(rule["requiredModules"]),
        "consumerEvidence": evidence,
    }
    for index, benchmark in enumerate(benchmarks):
        if not isinstance(benchmark, dict):
            raise ValueError(f"Benchmark entry {index} is not an object")
        for field, value in derived.items():
            existing = benchmark.get(field)
            if existing is not None and existing != value:
                raise ValueError(
                    f"Benchmark {benchmark.get('name', index)!r} contains {field}={existing!r}, "
                    f"but suite {suite_name!r} derives {value!r}"
                )
            benchmark[field] = value
    return evidence


def annotate_file(
    source: Path,
    target: Path,
    suite_name: str,
    root: Path,
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    value = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(value, list) or not value:
        raise ValueError(f"Benchmark result {source} must be a non-empty JSON array")
    catalog, rules = load_contracts(root)
    annotate_benchmarks(value, suite_name, catalog, rules)
    target.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return catalog, rules


def postprocess_history(
    data_file: Path,
    suite_name: str,
    commit: str,
    catalog: dict[str, dict[str, Any]],
    rules: dict[str, dict[str, Any]],
) -> None:
    if not catalog:
        return
    content = data_file.read_text(encoding="utf-8")
    if not content.startswith(SCRIPT_PREFIX):
        raise ValueError(f"Benchmark data file {data_file} has an invalid prefix")
    data = json.loads(content[len(SCRIPT_PREFIX) :])
    data["consumerRelevanceSchemaVersion"] = 1
    data["consumerCatalog"] = catalog
    rule = rules[suite_name]
    evidence = {consumer_id: dict(catalog[consumer_id]) for consumer_id in rule["consumers"]}
    history = data.get("entries", {}).get(suite_name, [])
    current = next(
        (entry for entry in reversed(history) if entry.get("commit", {}).get("id") == commit),
        None,
    )
    if current is None:
        raise ValueError(f"Published suite {suite_name!r} has no entry for commit {commit}")
    current["consumerEvidence"] = evidence
    data_file.write_text(
        SCRIPT_PREFIX + json.dumps(data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
