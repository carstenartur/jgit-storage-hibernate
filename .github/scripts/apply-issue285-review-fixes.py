#!/usr/bin/env python3
"""Address fail-closed numeric and contextual-diagnostic review findings."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


schema_path = Path(".github/scripts/jmh_evidence_schema.py")
schema = schema_path.read_text(encoding="utf-8")
schema = replace_once(
    schema,
    "from __future__ import annotations\n\nfrom typing import Any\n",
    "from __future__ import annotations\n\nimport math\nfrom typing import Any\n",
    "schema math import",
)
schema = replace_once(
    schema,
    '''def require_int_field(mapping: Any, key: str, context: str) -> int:
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
    '''def require_int_field(mapping: Any, key: str, context: str) -> int:
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
''',
    "strict integer validation",
)
schema_path.write_text(schema, encoding="utf-8")


layout_path = Path(".github/scripts/convert-jmh-pack-storage-layout.py")
layout = layout_path.read_text(encoding="utf-8")
layout = replace_once(
    layout,
    '''def _finite(value: Any, name: str) -> float:
    try:
        number = float(value)
''',
    '''def _finite(value: Any, name: str) -> float:
    if isinstance(value, bool):
        raise ValueError(f"Malformed {name}")
    try:
        number = float(value)
''',
    "layout boolean finite rejection",
)
layout = replace_once(
    layout,
    '''def _optional_finite(value: Any, name: str) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
''',
    '''def _optional_finite(value: Any, name: str) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError(f"Malformed {name}")
    try:
        number = float(value)
''',
    "layout optional boolean rejection",
)
layout = replace_once(
    layout,
    '''def _milliseconds(score: float, unit: str) -> float:
''',
    '''def _milliseconds(score: float, unit: str, context: str) -> float:
''',
    "layout millisecond signature",
)
layout = replace_once(
    layout,
    '''    except KeyError as failure:
        raise ValueError(f"Unsupported pack-layout score unit: {unit!r}") from failure
    if not math.isfinite(value):
        raise ValueError("Non-finite pack-layout score")
''',
    '''    except KeyError as failure:
        raise ValueError(
            f"Unsupported {context} score unit: {unit!r}"
        ) from failure
    if not math.isfinite(value):
        raise ValueError(f"Non-finite {context} score")
''',
    "layout contextual unit error",
)
layout = layout.replace(
    'return _milliseconds(score, unit)\n',
    'return _milliseconds(score, unit, context)\n',
)
layout = replace_once(
    layout,
    '''    score = _milliseconds(
        _finite(
            require_field(metric, "score", metric_context),
            f"{metric_context} score",
        ),
        unit,
    )
''',
    '''    score = _milliseconds(
        _finite(
            require_field(metric, "score", metric_context),
            f"{metric_context} score",
        ),
        unit,
        metric_context,
    )
''',
    "layout primary unit context",
)
layout = replace_once(
    layout,
    '    error = None if raw_error is None else _milliseconds(raw_error, unit)\n',
    '''    error = (
        None
        if raw_error is None
        else _milliseconds(raw_error, unit, f"{metric_context} scoreError")
    )
''',
    "layout uncertainty unit context",
)
layout = layout.replace(
    'raise ValueError(f"Malformed secondary metric {name!r}")',
    'raise ValueError(f"Malformed {context} secondary metric {name!r}")',
)
layout = layout.replace(
    'f"Malformed rawData for secondary metric {name!r}"',
    'f"Malformed {context} rawData for secondary metric {name!r}"',
)
layout = layout.replace(
    'f"Inconsistent rawData fork lengths for secondary metric {name!r}"',
    'f"Inconsistent {context} rawData fork lengths for secondary metric {name!r}"',
)
layout = layout.replace(
    'raw_value, f"rawData for secondary metric {name!r}"',
    'raw_value, f"{context} rawData for secondary metric {name!r}"',
)
layout = layout.replace(
    'f"Secondary metric {name!r} changed across JMH iterations: "',
    'f"{context} secondary metric {name!r} changed across JMH iterations: "',
)
layout = layout.replace(
    'raise ValueError(f"Malformed profiler metric {key!r}")',
    'raise ValueError(f"Malformed {context} profiler metric {key!r}")',
)
layout_path.write_text(layout, encoding="utf-8")


concurrency_path = Path(
    ".github/scripts/convert-jmh-pack-storage-layout-concurrency.py"
)
concurrency = concurrency_path.read_text(encoding="utf-8")
concurrency = replace_once(
    concurrency,
    '''def _finite(value: Any, context: str) -> float:
    try:
        number = float(value)
''',
    '''def _finite(value: Any, context: str) -> float:
    if isinstance(value, bool):
        raise ValueError(f"Malformed {context}")
    try:
        number = float(value)
''',
    "concurrency boolean finite rejection",
)
concurrency = replace_once(
    concurrency,
    '''def _optional_finite(value: Any, context: str) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
''',
    '''def _optional_finite(value: Any, context: str) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError(f"Malformed {context}")
    try:
        number = float(value)
''',
    "concurrency optional boolean rejection",
)
concurrency = replace_once(
    concurrency,
    '''def _milliseconds(value: float, unit: str) -> float:
''',
    '''def _milliseconds(value: float, unit: str, context: str) -> float:
''',
    "concurrency millisecond signature",
)
concurrency = replace_once(
    concurrency,
    '''    except KeyError as failure:
        raise ValueError(f"Unsupported concurrency score unit: {unit!r}") from failure
    if not math.isfinite(converted):
        raise ValueError("Non-finite concurrency score")
''',
    '''    except KeyError as failure:
        raise ValueError(
            f"Unsupported {context} score unit: {unit!r}"
        ) from failure
    if not math.isfinite(converted):
        raise ValueError(f"Non-finite {context} score")
''',
    "concurrency contextual unit error",
)
concurrency = replace_once(
    concurrency,
    '    return _milliseconds(value, unit)\n',
    '    return _milliseconds(value, unit, context)\n',
    "concurrency percentile unit context",
)
concurrency = replace_once(
    concurrency,
    '    return None if value is None else _milliseconds(value, unit)\n',
    '''    return (
        None
        if value is None
        else _milliseconds(value, unit, f"{context} scoreError")
    )
''',
    "concurrency uncertainty unit context",
)
concurrency = replace_once(
    concurrency,
    '    score = _milliseconds(primary_score, unit)\n',
    '    score = _milliseconds(primary_score, unit, metric_context)\n',
    "concurrency primary unit context",
)
concurrency = concurrency.replace(
    'raise ValueError(f"Malformed concurrency secondary metric {name!r}")',
    'raise ValueError(f"Malformed {context} concurrency secondary metric {name!r}")',
)
concurrency = concurrency.replace(
    'f"Malformed rawData for concurrency secondary metric {name!r}"',
    'f"Malformed {context} rawData for concurrency secondary metric {name!r}"',
)
concurrency = concurrency.replace(
    '"Inconsistent rawData fork lengths for concurrency "\n                        f"secondary metric {name!r}"',
    'f"Inconsistent {context} rawData fork lengths for concurrency "\n                        f"secondary metric {name!r}"',
)
concurrency = concurrency.replace(
    'f"Non-finite rawData for concurrency secondary metric {name!r}"',
    'f"Non-finite {context} rawData for concurrency secondary metric {name!r}"',
)
concurrency = concurrency.replace(
    'f"Concurrency secondary metric {name!r} changed across JMH iterations: {samples!r}"',
    'f"{context} concurrency secondary metric {name!r} changed across JMH iterations: {samples!r}"',
)
concurrency = concurrency.replace(
    'raise ValueError(f"Non-finite concurrency secondary metric {name!r}")',
    'raise ValueError(f"Non-finite {context} concurrency secondary metric {name!r}")',
)
concurrency = concurrency.replace(
    'raise ValueError(f"Concurrency result is missing secondary metric {name!r}")',
    'raise ValueError(f"{context} is missing secondary metric {name!r}")',
)
concurrency_path.write_text(concurrency, encoding="utf-8")


layout_test_path = Path(".github/scripts/test_convert_jmh_pack_storage_layout.py")
layout_tests = layout_test_path.read_text(encoding="utf-8")
layout_tests = replace_once(
    layout_tests,
    '''    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    '''    def test_boolean_primary_values_are_rejected(self) -> None:
        for field in ("score", "percentile"):
            with self.subTest(field=field):
                result = self.result("postgresql", "write", 1024, 10.0)
                if field == "score":
                    result["primaryMetric"]["score"] = True
                else:
                    result["primaryMetric"]["scorePercentiles"]["95.0"] = True
                with self.assertRaisesRegex(ValueError, "operation='write'"):
                    CONVERTER.convert([result])

    def test_non_integral_and_infinite_integer_parameters_are_rejected(self) -> None:
        for value in (1024.5, math.inf, -math.inf):
            with self.subTest(value=value):
                result = self.result("postgresql", "write", 1024, 10.0)
                result["params"]["chunkKiB"] = value
                with self.assertRaisesRegex(ValueError, "chunkKiB"):
                    CONVERTER.convert([result])

    def test_unsupported_score_unit_preserves_coordinate_context(self) -> None:
        result = self.result("postgresql", "write", 1024, 10.0)
        result["primaryMetric"]["scoreUnit"] = "bogus"
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([result])
        message = str(raised.exception)
        self.assertIn("Unsupported", message)
        self.assertIn("operation='write'", message)
        self.assertIn("chunkKiB='1024'", message)

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    "layout review regressions",
)
layout_tests = replace_once(
    layout_tests,
    '''        with self.assertRaisesRegex(ValueError, "fork lengths"):
            CONVERTER.convert([result])

    def test_nan_score_error_becomes_json_null(self) -> None:
''',
    '''        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([result])
        message = str(raised.exception)
        self.assertIn("fork lengths", message)
        self.assertIn("operation='write'", message)

    def test_nan_score_error_becomes_json_null(self) -> None:
''',
    "layout fork-context assertion",
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
    '''    def test_boolean_primary_values_are_rejected(self) -> None:
        for field in ("score", "percentile"):
            with self.subTest(field=field):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                if field == "score":
                    current["primaryMetric"]["score"] = True
                else:
                    current["primaryMetric"]["scorePercentiles"]["95.0"] = True
                with self.assertRaisesRegex(ValueError, "operation='write'"):
                    CONVERTER.convert([current, candidate])

    def test_non_integral_and_infinite_integer_parameters_are_rejected(self) -> None:
        for value in (1.5, math.inf, -math.inf):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                current["params"]["concurrency"] = value
                with self.assertRaisesRegex(ValueError, "concurrency"):
                    CONVERTER.convert([current, candidate])

    def test_unsupported_score_unit_preserves_coordinate_context(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        current["primaryMetric"]["scoreUnit"] = "bogus"
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([current, candidate])
        message = str(raised.exception)
        self.assertIn("Unsupported", message)
        self.assertIn("operation='write'", message)
        self.assertIn("concurrency='1'", message)

    def test_missing_current_layout_baseline_is_rejected(self) -> None:
''',
    "concurrency review regressions",
)
concurrency_tests = replace_once(
    concurrency_tests,
    '''        with self.assertRaisesRegex(ValueError, "fork lengths"):
            CONVERTER.convert([current, candidate])

    def test_present_but_invalid_raw_data_is_rejected(self) -> None:
''',
    '''        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([current, candidate])
        message = str(raised.exception)
        self.assertIn("fork lengths", message)
        self.assertIn("operation='write'", message)
        self.assertIn("concurrency='1'", message)

    def test_present_but_invalid_raw_data_is_rejected(self) -> None:
''',
    "concurrency fork-context assertion",
)
concurrency_test_path.write_text(concurrency_tests, encoding="utf-8")
