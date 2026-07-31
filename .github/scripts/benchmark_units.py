#!/usr/bin/env python3
"""Canonical unit conversion for benchmark history and comparison charts."""

from __future__ import annotations

import math
from typing import Any

CANONICAL_UNIT = "ms/op"

_TIME_PER_OPERATION_TO_MS = {
    "ns/op": 1e-6,
    "us/op": 1e-3,
    "µs/op": 1e-3,
    "ms/op": 1.0,
    "s/op": 1_000.0,
    "min/op": 60_000.0,
}

_THROUGHPUT_PERIOD_TO_MS = {
    "ops/ns": 1e-6,
    "ops/us": 1e-3,
    "ops/µs": 1e-3,
    "ops/ms": 1.0,
    "ops/s": 1_000.0,
    "ops/min": 60_000.0,
}

SUPPORTED_UNITS = frozenset(_TIME_PER_OPERATION_TO_MS | _THROUGHPUT_PERIOD_TO_MS)


def _finite_number(value: Any, field: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{field} must be finite, got {value!r}")
    return number


def normalize_measurement(
    value: Any,
    error: Any,
    unit: str,
) -> tuple[float, float]:
    """Convert a timing or throughput measurement to milliseconds per operation.

    Throughput errors are propagated through the reciprocal using the first-order
    derivative. JMH score errors are small confidence-interval estimates, so this
    keeps the custom dashboard's symmetric ``range`` representation meaningful.
    """

    numeric_value = _finite_number(value, "value")
    numeric_error = _finite_number(error, "error")
    if numeric_error < 0:
        raise ValueError(f"error must not be negative, got {error!r}")

    time_factor = _TIME_PER_OPERATION_TO_MS.get(unit)
    if time_factor is not None:
        return numeric_value * time_factor, numeric_error * time_factor

    throughput_period = _THROUGHPUT_PERIOD_TO_MS.get(unit)
    if throughput_period is not None:
        if numeric_value <= 0:
            raise ValueError(
                f"throughput must be positive to convert {unit} to {CANONICAL_UNIT}, "
                f"got {value!r}"
            )
        normalized_value = throughput_period / numeric_value
        normalized_error = throughput_period * numeric_error / (numeric_value * numeric_value)
        return normalized_value, normalized_error

    raise ValueError(
        f"Unsupported benchmark unit {unit!r}; expected one of {sorted(SUPPORTED_UNITS)}"
    )


def normalize_benchmark(benchmark: dict[str, Any]) -> dict[str, Any]:
    """Return one custom benchmark entry normalized to ``ms/op``."""

    normalized = dict(benchmark)
    original_unit = str(benchmark["unit"])
    original_value = benchmark["value"]
    has_range = "range" in benchmark
    original_range = benchmark.get("range", 0.0)
    value, error = normalize_measurement(original_value, original_range, original_unit)
    normalized["value"] = value
    normalized["unit"] = CANONICAL_UNIT
    if has_range:
        normalized["range"] = error

    if original_unit != CANONICAL_UNIT:
        original_metric = f"Original metric: {original_value} {original_unit}"
        existing_extra = normalized.get("extra")
        normalized["extra"] = (
            f"{existing_extra}\n{original_metric}" if existing_extra else original_metric
        )

    return normalized
