#!/usr/bin/env python3
"""Address the remaining suppressed issue #285 review findings."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


concurrency_path = Path(
    ".github/scripts/convert-jmh-pack-storage-layout-concurrency.py"
)
concurrency = concurrency_path.read_text(encoding="utf-8")
concurrency = replace_once(
    concurrency,
    '''                for raw_value in fork:
                    try:
                        value = float(raw_value)
                    except (TypeError, ValueError) as failure:
                        raise ValueError(
                            f"Malformed {context} rawData for concurrency secondary metric {name!r}"
                        ) from failure
                    if not math.isfinite(value):
                        raise ValueError(
                            f"Non-finite {context} rawData for concurrency secondary metric {name!r}"
                        )
                    samples.append(value)
''',
    '''                for raw_value in fork:
                    samples.append(
                        _finite(
                            raw_value,
                            f"{context} rawData for concurrency secondary metric {name!r}",
                        )
                    )
''',
    "concurrency raw-data numeric validation",
)
concurrency = replace_once(
    concurrency,
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
        if not math.isfinite(value):
            raise ValueError(f"Non-finite {context} concurrency secondary metric {name!r}")
        return value
''',
    '''        metric_context = f"{context} concurrency secondary metric {name!r}"
        return _finite(
            require_field(metric, "score", metric_context),
            f"{metric_context} score",
        )
''',
    "concurrency secondary score detail preservation",
)
concurrency = replace_once(
    concurrency,
    '''    score = _milliseconds(primary_score, unit, metric_context)
    if not math.isfinite(score) or score <= 0.0:
        raise ValueError("Concurrency latency must be finite and positive")
''',
    '''    score = _milliseconds(primary_score, unit, metric_context)
    if score <= 0.0:
        raise ValueError(f"Malformed {metric_context}: score must be positive")
''',
    "positive concurrency latency context",
)
concurrency_path.write_text(concurrency, encoding="utf-8")


layout_test_path = Path(".github/scripts/test_convert_jmh_pack_storage_layout.py")
layout_tests = layout_test_path.read_text(encoding="utf-8")
layout_tests = replace_once(
    layout_tests,
    '''    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    '''    def test_missing_secondary_score_preserves_field_detail(self) -> None:
        result = self.result("postgresql", "write", 1024, 10.0)
        del result["secondaryMetrics"][
            "LayoutCounters.configuredChunkBytes"
        ]["score"]
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([result])
        message = str(raised.exception)
        self.assertIn("missing field 'score'", message)
        self.assertIn("operation='write'", message)

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
''',
    "layout missing secondary score regression",
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
    '''    def test_non_positive_primary_score_preserves_coordinate_context(self) -> None:
        for value in (0.0, -1.0):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, value)
                candidate = self.result("write", 1, 4096, 9.0)
                with self.assertRaises(ValueError) as raised:
                    CONVERTER.convert([current, candidate])
                message = str(raised.exception)
                self.assertIn("score must be positive", message)
                self.assertIn("operation='write'", message)
                self.assertIn("concurrency='1'", message)

    def test_missing_secondary_score_preserves_field_detail(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        del current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]["score"]
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([current, candidate])
        message = str(raised.exception)
        self.assertIn("missing field 'score'", message)
        self.assertIn("operation='write'", message)
        self.assertIn("concurrency='1'", message)

    def test_boolean_secondary_values_are_rejected(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]["score"] = True
        with self.assertRaisesRegex(ValueError, "configuredChunkBytes"):
            CONVERTER.convert([current, candidate])

    def test_missing_current_layout_baseline_is_rejected(self) -> None:
''',
    "concurrency suppressed-review regressions",
)
concurrency_test_path.write_text(concurrency_tests, encoding="utf-8")


for workflow_name in (
    "pack-storage-layout.yml",
    "pack-storage-layout-concurrency.yml",
):
    path = Path(".github/workflows") / workflow_name
    workflow = path.read_text(encoding="utf-8")
    converter_line = (
        "      - '.github/scripts/convert-jmh-pack-storage-layout-concurrency.py'\n"
        if "concurrency" in workflow_name
        else "      - '.github/scripts/convert-jmh-pack-storage-layout.py'\n"
    )
    helper_line = "      - '.github/scripts/jmh_evidence_schema.py'\n"
    if workflow.count(converter_line) != 2:
        raise SystemExit(
            f"Expected two path-filter anchors in {workflow_name}, "
            f"found {workflow.count(converter_line)}"
        )
    workflow = workflow.replace(converter_line, converter_line + helper_line)

    compile_anchor = "          python3 -m py_compile \\\n"
    compile_helper = (
        "            .github/scripts/jmh_evidence_schema.py \\\n"
    )
    compile_count = workflow.count(compile_anchor)
    if compile_count < 1:
        raise SystemExit(f"No py_compile block found in {workflow_name}")
    workflow = workflow.replace(
        compile_anchor,
        compile_anchor + compile_helper,
    )
    path.write_text(workflow, encoding="utf-8")
