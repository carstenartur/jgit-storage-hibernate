#!/usr/bin/env python3
"""Small fail-closed helpers for validating JMH JSON evidence structures."""

from __future__ import annotations

import math
from typing import Any


def require_array(value: Any, context: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"Malformed {context}: expected JSON array")
    return value


def require_object(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"Malformed {context}: expected JSON object")
    return value


def result_context(
    result: Any,
    label: str,
    parameter_names: tuple[str, ...],
) -> str:
    if not isinstance(result, dict):
        return label
    parts: list[str] = []
    benchmark = result.get("benchmark")
    if isinstance(benchmark, str) and benchmark:
        parts.append(f"benchmark={benchmark!r}")
    params = result.get("params")
    if isinstance(params, dict):
        for name in parameter_names:
            if name in params:
                parts.append(f"{name}={params[name]!r}")
    return label if not parts else f"{label} ({', '.join(parts)})"


def require_field(mapping: Any, key: str, context: str) -> Any:
    required_mapping = require_object(mapping, context)
    if key not in required_mapping:
        raise ValueError(f"Malformed {context}: missing field {key!r}")
    return required_mapping[key]


def require_object_field(
    mapping: Any,
    key: str,
    context: str,
) -> dict[str, Any]:
    value = require_field(mapping, key, context)
    return require_object(value, f"{context} field {key!r}")


def optional_object_field(
    mapping: Any,
    key: str,
    context: str,
) -> dict[str, Any] | None:
    required_mapping = require_object(mapping, context)
    if key not in required_mapping:
        return None
    return require_object(required_mapping[key], f"{context} field {key!r}")


def require_string_field(mapping: Any, key: str, context: str) -> str:
    value = require_field(mapping, key, context)
    if not isinstance(value, str) or not value:
        raise ValueError(
            f"Malformed {context}: field {key!r} must be a non-empty string"
        )
    return value


def require_int_field(mapping: Any, key: str, context: str) -> int:
    value = require_field(mapping, key, context)
    if isinstance(value, bool):
        raise ValueError(f"Malformed {context}: field {key!r} must be an integer")
    if isinstance(value, float) and (
        not math.isfinite(value) or not value.is_integer()
    ):
        raise ValueError(f"Malformed {context}: field {key!r} must be an integer")
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError) as failure:
        raise ValueError(
            f"Malformed {context}: field {key!r} must be an integer"
        ) from failure
