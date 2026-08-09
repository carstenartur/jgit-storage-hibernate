#!/usr/bin/env python3
"""Resolve benchmark-suite consumer relevance from pinned compatibility module evidence."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class RelevanceError(RuntimeError):
    pass


def _load_object(path: Path) -> dict[str, Any]:
    parsed = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(parsed, dict):
        raise RelevanceError(f"{path} must contain a JSON object")
    return parsed


def resolve(
    consumer_descriptor: Path,
    relevance_map: Path,
    suite_name: str,
) -> dict[str, object] | None:
    consumers = _load_object(consumer_descriptor)
    relevance = _load_object(relevance_map)
    if consumers.get("schemaVersion") != 2:
        raise RelevanceError("consumer compatibility schemaVersion must be 2")
    if relevance.get("schemaVersion") != 1:
        raise RelevanceError("benchmark relevance schemaVersion must be 1")

    suites = relevance.get("suites")
    if not isinstance(suites, dict):
        raise RelevanceError("benchmark relevance 'suites' must be an object")
    raw_suite = suites.get(suite_name)
    if raw_suite is None:
        return None
    if not isinstance(raw_suite, dict):
        raise RelevanceError(f"suite {suite_name!r} relevance must be an object")
    contract = raw_suite.get("contract")
    required_modules = raw_suite.get("requiredModules")
    if not isinstance(contract, str) or not contract.strip():
        raise RelevanceError(f"suite {suite_name!r} must define a non-empty contract")
    if not isinstance(required_modules, list) or not required_modules:
        raise RelevanceError(f"suite {suite_name!r} must define requiredModules")
    required = {str(value) for value in required_modules}

    raw_consumers = consumers.get("consumers")
    if not isinstance(raw_consumers, list):
        raise RelevanceError("consumer compatibility 'consumers' must be a list")
    matching: list[str] = []
    evidence: list[dict[str, object]] = []
    for raw_consumer in raw_consumers:
        if not isinstance(raw_consumer, dict):
            raise RelevanceError("consumer entries must be objects")
        consumer_id = raw_consumer.get("id")
        modules = raw_consumer.get("expectedModules")
        ref = raw_consumer.get("ref")
        if not isinstance(consumer_id, str) or not isinstance(modules, list):
            raise RelevanceError("consumer id/expectedModules are invalid")
        declared = {str(value) for value in modules}
        relevant = required.issubset(declared)
        if relevant:
            matching.append(consumer_id)
        evidence.append(
            {
                "consumer": consumer_id,
                "ref": ref,
                "modules": sorted(declared),
                "relevant": relevant,
            }
        )

    return {
        "consumers": sorted(matching),
        "contract": contract.strip(),
        "requiredModules": sorted(required),
        "consumerRelevanceEvidence": evidence,
        "consumerRelevanceSemantics": "library-impact-not-application-measurement",
    }


def apply_to_benches(
    benches: list[dict[str, Any]], metadata: dict[str, object] | None
) -> list[dict[str, Any]]:
    if metadata is None:
        return benches
    result: list[dict[str, Any]] = []
    for benchmark in benches:
        enriched = dict(benchmark)
        for key, value in metadata.items():
            enriched[key] = value
        result.append(enriched)
    return result
