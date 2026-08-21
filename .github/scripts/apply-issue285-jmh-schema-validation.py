#!/usr/bin/env python3
"""Apply consistent, contextual JMH schema validation for issue #285."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


schema_path = Path(".github/scripts/jmh_evidence_schema.py")
schema_path.write_text(
    '''#!/usr/bin/env python3
"""Small fail-closed helpers for validating JMH JSON evidence structures."""

from __future__ import annotations

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
    try:
        return int(value)
    except (TypeError, ValueError) as failure:
        raise ValueError(
            f"Malformed {context}: field {key!r} must be an integer"
        ) from failure
''',
    encoding="utf-8",
)


layout_path = Path(".github/scripts/convert-jmh-pack-storage-layout.py")
layout = layout_path.read_text(encoding="utf-8")
layout = replace_once(
    layout,
    "from typing import Any\n",
    '''from typing import Any

from jmh_evidence_schema import (
    optional_object_field,
    require_array,
    require_field,
    require_int_field,
    require_object,
    require_object_field,
    require_string_field,
    result_context,
)
''',
    "layout schema imports",
)
layout = replace_once(
    layout,
    'SPARSE_OPERATIONS = {"short-read", "random-read"}\n',
    '''SPARSE_OPERATIONS = {"short-read", "random-read"}
PARAMETER_NAMES = (
    "backend",
    "operation",
    "payloadKiB",
    "chunkKiB",
    "inlineKiB",
    "retainedMiB",
    "readAheadKiB",
)
''',
    "layout parameter names",
)
layout = replace_once(
    layout,
    '    for key, metric in result.get("secondaryMetrics", {}).items():\n',
    '''    context = result_context(result, "pack-layout result", PARAMETER_NAMES)
    required_result = require_object(result, context)
    secondary_metrics = require_object_field(
        required_result, "secondaryMetrics", context
    )
    for key, metric in secondary_metrics.items():
''',
    "layout secondary-metrics traversal",
)
layout = replace_once(
    layout,
    '        value = _finite(metric.get("score", default), f"secondary metric {name!r}")\n',
    '''        value = _finite(
            require_field(
                metric,
                "score",
                f"{context} secondary metric {name!r}",
            ),
            f"{context} secondary metric {name!r}",
        )
''',
    "layout secondary score validation",
)
layout = replace_once(
    layout,
    '''def _profiler(result: dict[str, Any], suffix: str) -> float | None:
    for key, metric in result.get("secondaryMetrics", {}).items():
''',
    '''def _profiler(result: dict[str, Any], suffix: str) -> float | None:
    context = result_context(result, "pack-layout result", PARAMETER_NAMES)
    required_result = require_object(result, context)
    secondary_metrics = require_object_field(
        required_result, "secondaryMetrics", context
    )
    for key, metric in secondary_metrics.items():
''',
    "layout profiler traversal",
)
layout = replace_once(
    layout,
    '''        return _optional_finite(metric.get("score"), f"profiler metric {key!r}")
''',
    '''        return _optional_finite(
            require_field(metric, "score", f"{context} profiler metric {key!r}"),
            f"{context} profiler metric {key!r}",
        )
''',
    "layout profiler score validation",
)
layout = replace_once(
    layout,
    '''def _percentile(metric: dict[str, Any], percentile: str, fallback: float) -> float:
    values = metric.get("scorePercentiles")
    if not isinstance(values, dict) or percentile not in values:
        return fallback
    score = _finite(values[percentile], f"primary percentile {percentile}")
    return _milliseconds(score, str(metric["scoreUnit"]))
''',
    '''def _percentile(
    metric: dict[str, Any],
    percentile: str,
    fallback: float,
    unit: str,
    context: str,
) -> float:
    values = optional_object_field(metric, "scorePercentiles", context)
    if values is None or percentile not in values:
        return fallback
    score = _finite(
        values[percentile], f"{context} percentile {percentile}"
    )
    return _milliseconds(score, unit)
''',
    "layout percentile validation",
)
layout = replace_once(
    layout,
    '''def _row(result: dict[str, Any]) -> dict[str, Any]:
    params = result.get("params", {})
    operation = str(params.get("operation", ""))
    if operation not in OPERATIONS:
        raise ValueError(f"Unsupported pack-layout operation: {operation!r}")
    backend = str(params.get("backend", ""))
    if not backend:
        raise ValueError("Pack-layout result is missing backend")
    payload_kib = int(params["payloadKiB"])
    chunk_kib = int(params["chunkKiB"])
    inline_kib = int(params["inlineKiB"])
    retained_mib = int(params["retainedMiB"])
    read_ahead_kib = int(params["readAheadKiB"])
''',
    '''def _row(result: Any) -> dict[str, Any]:
    context = result_context(result, "pack-layout result", PARAMETER_NAMES)
    required_result = require_object(result, context)
    params = require_object_field(required_result, "params", context)
    require_object_field(required_result, "secondaryMetrics", context)
    context = result_context(required_result, "pack-layout result", PARAMETER_NAMES)
    params_context = f"{context} params"
    operation = require_string_field(params, "operation", params_context)
    if operation not in OPERATIONS:
        raise ValueError(f"Unsupported pack-layout operation in {context}: {operation!r}")
    backend = require_string_field(params, "backend", params_context)
    payload_kib = require_int_field(params, "payloadKiB", params_context)
    chunk_kib = require_int_field(params, "chunkKiB", params_context)
    inline_kib = require_int_field(params, "inlineKiB", params_context)
    retained_mib = require_int_field(params, "retainedMiB", params_context)
    read_ahead_kib = require_int_field(params, "readAheadKiB", params_context)
''',
    "layout row parameter validation",
)
layout = replace_once(
    layout,
    '''    metric = result["primaryMetric"]
    if not isinstance(metric, dict):
        raise ValueError("Malformed primary metric")
    unit = str(metric["scoreUnit"])
    score = _milliseconds(_finite(metric["score"], "primary score"), unit)
    raw_error = _optional_finite(metric.get("scoreError"), "primary score error")
''',
    '''    metric = require_object_field(required_result, "primaryMetric", context)
    metric_context = f"{context} primaryMetric"
    unit = require_string_field(metric, "scoreUnit", metric_context)
    score = _milliseconds(
        _finite(
            require_field(metric, "score", metric_context),
            f"{metric_context} score",
        ),
        unit,
    )
    raw_error = _optional_finite(
        metric.get("scoreError"), f"{metric_context} scoreError"
    )
''',
    "layout primary metric validation",
)
layout = replace_once(
    layout,
    '''        "p50Millis": _percentile(metric, "50.0", score),
        "p95Millis": _percentile(metric, "95.0", score),
        "p99Millis": _percentile(metric, "99.0", score),
''',
    '''        "p50Millis": _percentile(metric, "50.0", score, unit, metric_context),
        "p95Millis": _percentile(metric, "95.0", score, unit, metric_context),
        "p99Millis": _percentile(metric, "99.0", score, unit, metric_context),
''',
    "layout percentile calls",
)
layout = replace_once(
    layout,
    '''def convert(results: list[dict[str, Any]]) -> dict[str, Any]:
    rows = [_row(result) for result in results]
''',
    '''def convert(results: Any) -> dict[str, Any]:
    required_results = require_array(results, "pack-layout JMH result")
    rows = [_row(result) for result in required_results]
''',
    "layout top-level array validation",
)
layout_path.write_text(layout, encoding="utf-8")


concurrency_path = Path(
    ".github/scripts/convert-jmh-pack-storage-layout-concurrency.py"
)
concurrency = concurrency_path.read_text(encoding="utf-8")
concurrency = replace_once(
    concurrency,
    "from typing import Any\n",
    '''from typing import Any

from jmh_evidence_schema import (
    optional_object_field,
    require_array,
    require_field,
    require_int_field,
    require_object,
    require_object_field,
    require_string_field,
    result_context,
)
''',
    "concurrency schema imports",
)
concurrency = replace_once(
    concurrency,
    'SPARSE_OPERATIONS = {"short-read", "random-read"}\n',
    '''SPARSE_OPERATIONS = {"short-read", "random-read"}
PARAMETER_NAMES = (
    "backend",
    "operation",
    "payloadKiB",
    "chunkKiB",
    "inlineKiB",
    "retainedMiB",
    "readAheadKiB",
    "concurrency",
)
''',
    "concurrency parameter names",
)
concurrency = replace_once(
    concurrency,
    '    for key, metric in result.get("secondaryMetrics", {}).items():\n',
    '''    context = result_context(result, "concurrency result", PARAMETER_NAMES)
    required_result = require_object(result, context)
    secondary_metrics = require_object_field(
        required_result, "secondaryMetrics", context
    )
    for key, metric in secondary_metrics.items():
''',
    "concurrency secondary-metrics traversal",
)
concurrency = replace_once(
    concurrency,
    '''            samples: list[float] = []
            for fork in raw_data:
''',
    '''            samples: list[float] = []
            iteration_count: int | None = None
            for fork in raw_data:
''',
    "concurrency raw-data iteration count",
)
concurrency = replace_once(
    concurrency,
    '''                if not isinstance(fork, list) or not fork:
                    raise ValueError(
                        f"Malformed rawData for concurrency secondary metric {name!r}"
                    )
                for raw_value in fork:
''',
    '''                if not isinstance(fork, list) or not fork:
                    raise ValueError(
                        f"Malformed rawData for concurrency secondary metric {name!r}"
                    )
                if iteration_count is None:
                    iteration_count = len(fork)
                elif len(fork) != iteration_count:
                    raise ValueError(
                        "Inconsistent rawData fork lengths for concurrency "
                        f"secondary metric {name!r}"
                    )
                for raw_value in fork:
''',
    "concurrency raw-data shape validation",
)
concurrency = replace_once(
    concurrency,
    '''        try:
            value = float(metric.get("score", 0.0))
        except (TypeError, ValueError) as failure:
            raise ValueError(
                f"Malformed concurrency secondary metric {name!r}"
            ) from failure
''',
    '''        try:
            value = float(
                require_field(
                    metric,
                    "score",
                    f"{context} concurrency secondary metric {name!r}",
                )
            )
        except (TypeError, ValueError) as failure:
            raise ValueError(
                f"Malformed {context} concurrency secondary metric {name!r}"
            ) from failure
''',
    "concurrency secondary score validation",
)
concurrency = replace_once(
    concurrency,
    '''def _percentile(metric: dict[str, Any], name: str, fallback: float) -> float:
    values = metric.get("scorePercentiles")
    if not isinstance(values, dict) or name not in values:
        return fallback
    return _milliseconds(float(values[name]), str(metric["scoreUnit"]))
''',
    '''def _percentile(
    metric: dict[str, Any],
    name: str,
    fallback: float,
    unit: str,
    context: str,
) -> float:
    values = optional_object_field(metric, "scorePercentiles", context)
    if values is None or name not in values:
        return fallback
    try:
        value = float(values[name])
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {context} percentile {name}") from failure
    return _milliseconds(value, unit)
''',
    "concurrency percentile validation",
)
concurrency = replace_once(
    concurrency,
    '''def _row(result: dict[str, Any]) -> dict[str, Any]:
    params = result.get("params", {})
    operation = str(params.get("operation", ""))
    if operation not in OPERATIONS:
        raise ValueError(f"Unsupported concurrency operation: {operation!r}")
    backend = str(params.get("backend", ""))
    if not backend:
        raise ValueError("Concurrency result is missing backend")
    concurrency = int(params["concurrency"])
    if concurrency not in CONCURRENCY_LEVELS:
        raise ValueError(f"Unsupported concurrency level: {concurrency}")
    threads = int(result.get("threads", 0))
''',
    '''def _row(result: Any) -> dict[str, Any]:
    context = result_context(result, "concurrency result", PARAMETER_NAMES)
    required_result = require_object(result, context)
    params = require_object_field(required_result, "params", context)
    require_object_field(required_result, "secondaryMetrics", context)
    context = result_context(required_result, "concurrency result", PARAMETER_NAMES)
    params_context = f"{context} params"
    operation = require_string_field(params, "operation", params_context)
    if operation not in OPERATIONS:
        raise ValueError(f"Unsupported concurrency operation in {context}: {operation!r}")
    backend = require_string_field(params, "backend", params_context)
    concurrency = require_int_field(params, "concurrency", params_context)
    if concurrency not in CONCURRENCY_LEVELS:
        raise ValueError(f"Unsupported concurrency level in {context}: {concurrency}")
    threads = require_int_field(required_result, "threads", context)
''',
    "concurrency row leading validation",
)
concurrency = replace_once(
    concurrency,
    '''    chunk_kib = int(params["chunkKiB"])
    inline_kib = int(params["inlineKiB"])
    payload_kib = int(params["payloadKiB"])
    retained_mib = int(params["retainedMiB"])
    read_ahead_kib = int(params["readAheadKiB"])

    metric = result["primaryMetric"]
    score = _milliseconds(float(metric["score"]), str(metric["scoreUnit"]))
''',
    '''    chunk_kib = require_int_field(params, "chunkKiB", params_context)
    inline_kib = require_int_field(params, "inlineKiB", params_context)
    payload_kib = require_int_field(params, "payloadKiB", params_context)
    retained_mib = require_int_field(params, "retainedMiB", params_context)
    read_ahead_kib = require_int_field(params, "readAheadKiB", params_context)

    metric = require_object_field(required_result, "primaryMetric", context)
    metric_context = f"{context} primaryMetric"
    unit = require_string_field(metric, "scoreUnit", metric_context)
    try:
        primary_score = float(require_field(metric, "score", metric_context))
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {metric_context} score") from failure
    score = _milliseconds(primary_score, unit)
''',
    "concurrency parameter and primary metric validation",
)
concurrency = replace_once(
    concurrency,
    '''        "scoreErrorMillis": _milliseconds(
            float(metric.get("scoreError", 0.0)), str(metric["scoreUnit"])
        ),
        "p50Millis": _percentile(metric, "50.0", score),
        "p95Millis": _percentile(metric, "95.0", score),
        "p99Millis": _percentile(metric, "99.0", score),
''',
    '''        "scoreErrorMillis": _score_error(metric, unit, metric_context),
        "p50Millis": _percentile(metric, "50.0", score, unit, metric_context),
        "p95Millis": _percentile(metric, "95.0", score, unit, metric_context),
        "p99Millis": _percentile(metric, "99.0", score, unit, metric_context),
''',
    "concurrency metric calls",
)
concurrency = replace_once(
    concurrency,
    '''def _row(result: Any) -> dict[str, Any]:
''',
    '''def _score_error(metric: dict[str, Any], unit: str, context: str) -> float:
    try:
        value = float(metric.get("scoreError", 0.0))
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {context} scoreError") from failure
    return _milliseconds(value, unit)


def _row(result: Any) -> dict[str, Any]:
''',
    "concurrency score-error helper",
)
concurrency = replace_once(
    concurrency,
    '''def convert(results: list[dict[str, Any]]) -> dict[str, Any]:
    rows = [_row(result) for result in results]
''',
    '''def convert(results: Any) -> dict[str, Any]:
    required_results = require_array(results, "concurrency JMH result")
    rows = [_row(result) for result in required_results]
''',
    "concurrency top-level array validation",
)
concurrency_path.write_text(concurrency, encoding="utf-8")


layout_test_path = Path(".github/scripts/test_convert_jmh_pack_storage_layout.py")
layout_tests = layout_test_path.read_text(encoding="utf-8")
layout_tests = replace_once(
    layout_tests,
    '''    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    '''    def test_malformed_schema_is_reported_as_contextual_value_error(self) -> None:
        valid = self.result("postgresql", "write", 1024, 10.0)
        malformed_cases = []

        malformed_cases.append(("array", {}, "expected JSON array"))
        malformed_cases.append(("result", [None], "expected JSON object"))

        for field in ("params", "primaryMetric", "secondaryMetrics"):
            result = self.result("postgresql", "write", 1024, 10.0)
            result[field] = None
            malformed_cases.append((field, [result], field))

        for field in (
            "backend",
            "operation",
            "payloadKiB",
            "chunkKiB",
            "inlineKiB",
            "retainedMiB",
            "readAheadKiB",
        ):
            result = self.result("postgresql", "write", 1024, 10.0)
            del result["params"][field]
            malformed_cases.append((field, [result], field))

        for field in ("score", "scoreUnit"):
            result = self.result("postgresql", "write", 1024, 10.0)
            del result["primaryMetric"][field]
            malformed_cases.append((field, [result], field))

        result = self.result("postgresql", "write", 1024, 10.0)
        result["params"]["chunkKiB"] = "not-an-integer"
        malformed_cases.append(("integer", [result], "chunkKiB"))

        result = self.result("postgresql", "write", 1024, 10.0)
        result["primaryMetric"]["scorePercentiles"] = []
        malformed_cases.append(("percentiles", [result], "scorePercentiles"))

        result = self.result("postgresql", "write", 1024, 10.0)
        del result["secondaryMetrics"]["LayoutCounters.configuredChunkBytes"]["score"]
        malformed_cases.append(("secondary score", [result], "configuredChunkBytes"))

        for name, value, expected in malformed_cases:
            with self.subTest(name=name):
                with self.assertRaises(ValueError) as raised:
                    CONVERTER.convert(value)
                message = str(raised.exception)
                self.assertIn(expected, message)
                if name not in {"array", "result", "backend", "params"}:
                    self.assertIn("operation='write'", message)

        self.assertEqual("postgresql", valid["params"]["backend"])

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    "layout schema regression tests",
)
layout_test_path.write_text(layout_tests, encoding="utf-8")


concurrency_test_path = Path(
    ".github/scripts/test_convert_jmh_pack_storage_layout_concurrency.py"
)
concurrency_tests = concurrency_test_path.read_text(encoding="utf-8")
concurrency_tests = replace_once(
    concurrency_tests,
    '''    def test_missing_current_layout_baseline_is_rejected(self) -> None:
''',
    '''    def test_malformed_schema_is_reported_as_contextual_value_error(self) -> None:
        malformed_cases = []
        malformed_cases.append(("array", {}, "expected JSON array"))
        malformed_cases.append(("result", [None], "expected JSON object"))

        for field in ("params", "primaryMetric", "secondaryMetrics"):
            result = self.result("write", 1, 1024, 10.0)
            result[field] = None
            malformed_cases.append((field, [result], field))

        for field in (
            "backend",
            "operation",
            "payloadKiB",
            "chunkKiB",
            "inlineKiB",
            "retainedMiB",
            "readAheadKiB",
            "concurrency",
        ):
            result = self.result("write", 1, 1024, 10.0)
            del result["params"][field]
            malformed_cases.append((field, [result], field))

        result = self.result("write", 1, 1024, 10.0)
        del result["threads"]
        malformed_cases.append(("threads", [result], "threads"))

        for field in ("score", "scoreUnit"):
            result = self.result("write", 1, 1024, 10.0)
            del result["primaryMetric"][field]
            malformed_cases.append((field, [result], field))

        result = self.result("write", 1, 1024, 10.0)
        result["params"]["concurrency"] = "not-an-integer"
        malformed_cases.append(("integer", [result], "concurrency"))

        result = self.result("write", 1, 1024, 10.0)
        result["primaryMetric"]["scorePercentiles"] = []
        malformed_cases.append(("percentiles", [result], "scorePercentiles"))

        result = self.result("write", 1, 1024, 10.0)
        del result["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]["score"]
        malformed_cases.append(("secondary score", [result], "configuredChunkBytes"))

        for name, value, expected in malformed_cases:
            with self.subTest(name=name):
                with self.assertRaises(ValueError) as raised:
                    CONVERTER.convert(value)
                message = str(raised.exception)
                self.assertIn(expected, message)
                if name not in {"array", "result", "backend", "params"}:
                    self.assertIn("operation='write'", message)

    def test_missing_current_layout_baseline_is_rejected(self) -> None:
''',
    "concurrency schema regression tests",
)
concurrency_tests = replace_once(
    concurrency_tests,
    '''    def test_present_but_invalid_raw_data_is_rejected(self) -> None:
''',
    '''    def test_inconsistent_raw_data_fork_lengths_are_rejected(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        metric = current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]
        expected = float(metric["score"])
        metric["rawData"] = [[expected, expected], [expected]]
        with self.assertRaisesRegex(ValueError, "fork lengths"):
            CONVERTER.convert([current, candidate])

    def test_present_but_invalid_raw_data_is_rejected(self) -> None:
''',
    "concurrency fork-shape regression",
)
concurrency_test_path.write_text(concurrency_tests, encoding="utf-8")
