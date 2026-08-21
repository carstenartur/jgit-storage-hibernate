#!/usr/bin/env python3
"""Finish issue #285 by making concurrency evidence strictly serializable."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


converter_path = Path(
    ".github/scripts/convert-jmh-pack-storage-layout-concurrency.py"
)
converter = converter_path.read_text(encoding="utf-8")
converter = replace_once(
    converter,
    '''    "concurrency",
)


def _milliseconds(value: float, unit: str) -> float:
''',
    '''    "concurrency",
)


def _finite(value: Any, context: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {context}") from failure
    if not math.isfinite(number):
        raise ValueError(f"Non-finite {context}")
    return number


def _optional_finite(value: Any, context: str) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {context}") from failure
    return number if math.isfinite(number) else None


def _milliseconds(value: float, unit: str) -> float:
''',
    "concurrency finite-value helpers",
)
converter = replace_once(
    converter,
    '''    try:
        return value * factors[unit]
    except KeyError as failure:
        raise ValueError(f"Unsupported concurrency score unit: {unit!r}") from failure
''',
    '''    try:
        converted = value * factors[unit]
    except KeyError as failure:
        raise ValueError(f"Unsupported concurrency score unit: {unit!r}") from failure
    if not math.isfinite(converted):
        raise ValueError("Non-finite concurrency score")
    return converted
''',
    "concurrency millisecond conversion",
)
converter = replace_once(
    converter,
    '''    try:
        value = float(values[name])
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {context} percentile {name}") from failure
    return _milliseconds(value, unit)


def _score_error(metric: dict[str, Any], unit: str, context: str) -> float:
    try:
        value = float(metric.get("scoreError", 0.0))
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {context} scoreError") from failure
    return _milliseconds(value, unit)
''',
    '''    value = _finite(values[name], f"{context} percentile {name}")
    return _milliseconds(value, unit)


def _score_error(
    metric: dict[str, Any], unit: str, context: str
) -> float | None:
    value = _optional_finite(metric.get("scoreError"), f"{context} scoreError")
    return None if value is None else _milliseconds(value, unit)
''',
    "concurrency percentile and uncertainty validation",
)
converter = replace_once(
    converter,
    '''    try:
        primary_score = float(require_field(metric, "score", metric_context))
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {metric_context} score") from failure
    score = _milliseconds(primary_score, unit)
''',
    '''    primary_score = _finite(
        require_field(metric, "score", metric_context),
        f"{metric_context} score",
    )
    score = _milliseconds(primary_score, unit)
''',
    "concurrency primary score validation",
)
converter = replace_once(
    converter,
    '''def _format(value: float | None) -> str:
    return "–" if value is None else f"{value:.3f}%"


def main() -> None:
''',
    '''def _format(value: float | None) -> str:
    return "–" if value is None else f"{value:.3f}%"


def _strict_json(value: Any) -> str:
    return (
        json.dumps(
            value,
            indent=2,
            ensure_ascii=False,
            allow_nan=False,
        )
        + "\\n"
    )


def main() -> None:
''',
    "concurrency strict JSON helper",
)
converter = replace_once(
    converter,
    '''    (output / "pack-storage-layout-concurrency-comparison.json").write_text(
        json.dumps(report["comparison"], indent=2, ensure_ascii=False) + "\\n",
        encoding="utf-8",
    )
    (output / "pack-storage-layout-concurrency-evidence.json").write_text(
        json.dumps(
            {
                key: value
                for key, value in report.items()
                if key != "comparison"
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\\n",
        encoding="utf-8",
    )
''',
    '''    (output / "pack-storage-layout-concurrency-comparison.json").write_text(
        _strict_json(report["comparison"]),
        encoding="utf-8",
    )
    (output / "pack-storage-layout-concurrency-evidence.json").write_text(
        _strict_json(
            {
                key: value
                for key, value in report.items()
                if key != "comparison"
            }
        ),
        encoding="utf-8",
    )
''',
    "concurrency strict JSON serialization",
)
converter_path.write_text(converter, encoding="utf-8")


test_path = Path(
    ".github/scripts/test_convert_jmh_pack_storage_layout_concurrency.py"
)
tests = test_path.read_text(encoding="utf-8")
tests = replace_once(
    tests,
    "import importlib.util\nimport math\n",
    "import importlib.util\nimport json\nimport math\n",
    "concurrency test JSON import",
)
tests = replace_once(
    tests,
    '''    def test_missing_current_layout_baseline_is_rejected(self) -> None:
''',
    '''    def test_nan_score_error_becomes_json_null(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        current["primaryMetric"]["scoreError"] = math.nan

        report = CONVERTER.convert([current, candidate])
        current_row = next(
            row
            for row in report["evidence"]
            if row["chunkKiB"] == 1024
        )
        self.assertIsNone(current_row["scoreErrorMillis"])
        serialized = CONVERTER._strict_json(report)
        self.assertNotIn("NaN", serialized)
        self.assertIsNone(
            next(
                row
                for row in json.loads(serialized)["evidence"]
                if row["chunkKiB"] == 1024
            )["scoreErrorMillis"]
        )

    def test_missing_score_error_becomes_json_null(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        del current["primaryMetric"]["scoreError"]
        report = CONVERTER.convert([current, candidate])
        self.assertIsNone(
            next(
                row
                for row in report["evidence"]
                if row["chunkKiB"] == 1024
            )["scoreErrorMillis"]
        )

    def test_non_finite_primary_score_is_rejected(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, value)
                candidate = self.result("write", 1, 4096, 9.0)
                with self.assertRaisesRegex(ValueError, "primaryMetric score"):
                    CONVERTER.convert([current, candidate])

    def test_non_finite_primary_percentile_is_rejected(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                current["primaryMetric"]["scorePercentiles"]["95.0"] = value
                with self.assertRaisesRegex(
                    ValueError, "primaryMetric percentile"
                ):
                    CONVERTER.convert([current, candidate])

    def test_strict_json_rejects_unexpected_non_finite_values(self) -> None:
        with self.assertRaises(ValueError):
            CONVERTER._strict_json({"unexpected": math.nan})

    def test_missing_current_layout_baseline_is_rejected(self) -> None:
''',
    "concurrency strict JSON regressions",
)
test_path.write_text(tests, encoding="utf-8")
