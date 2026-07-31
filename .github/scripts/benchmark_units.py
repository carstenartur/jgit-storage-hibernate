#!/usr/bin/env python3
"""Normalize benchmark values to milliseconds per operation.

The public dashboard uses the smaller-is-better contract. JMH may emit the same
benchmark in a latency unit (for example ``ms/op``) or a throughput unit (for
example ``ops/s``) when its benchmark mode changes. Persisting those values
unchanged makes one time series switch direction and scale mid-history.
"""

from __future__ import annotations

import math
from typing import Any

NORMALIZED_UNIT = "ms/op"

_TIME_TO_MILLISECONDS = {
    "s/op": 1_000.0,
    "ms/op": 1.0,
    "us/op": 0.001,
    "µs/op": 0.001,
    "ns/op": 0.000001,
}

_THROUGHPUT_WINDOW_MILLISECONDS = {
    "ops/s": 1_000.0,
    "ops/ms": 1.0,
    "ops/us": 0.001,
    "ops/µs": 0.001,
    "ops/ns": 0.000001,
}


def _finite_number(value: Any, field: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{field} must be finite, got {value!r}")
    return number


def normalize_value_and_range(
    value: Any, range_value: Any | None, unit: str
) -> tuple[float, float | None]:
    """Return a value and optional symmetric range in ``ms/op``.

    For throughput, the central value is converted exactly. The symmetric error
    uses first-order propagation because JMH exposes one symmetric ``scoreError``
    while the reciprocal confidence interval is inherently asymmetric.
    """

    score = _finite_number(value, "value")
    error = None if range_value is None else _finite_number(range_value, "range")
    if error is not None and error < 0.0:
        raise ValueError(f"range must not be negative, got {range_value!r}")

    time_factor = _TIME_TO_MILLISECONDS.get(unit)
    if time_factor is not None:
        return score * time_factor, None if error is None else error * time_factor

    throughput_window = _THROUGHPUT_WINDOW_MILLISECONDS.get(unit)
    if throughput_window is not None:
        if score <= 0.0:
            raise ValueError(f"throughput must be positive, got {value!r} {unit}")
        normalized = throughput_window / score
        normalized_error = (
            None if error is None else throughput_window * error / (score * score)
        )
        return normalized, normalized_error

    raise ValueError(
        f"Unsupported benchmark unit {unit!r}; expected a JMH time/op or ops/time unit"
    )


def normalize_benchmark(benchmark: dict[str, Any]) -> dict[str, Any]:
    """Return a copy of one dashboard benchmark normalized to ``ms/op``."""

    unit = str(benchmark["unit"])
    normalized_value, normalized_range = normalize_value_and_range(
        benchmark["value"], benchmark.get("range"), unit
    )
    normalized = dict(benchmark)
    normalized["value"] = normalized_value
    normalized["unit"] = NORMALIZED_UNIT
    if normalized_range is None:
        normalized.pop("range", None)
    else:
        normalized["range"] = normalized_range

    if unit != NORMALIZED_UNIT:
        note = f"Chart unit normalized from {unit} to {NORMALIZED_UNIT}."
        extra = str(normalized.get("extra", "")).strip()
        normalized["extra"] = f"{extra}\n{note}" if extra else note
    return normalized
