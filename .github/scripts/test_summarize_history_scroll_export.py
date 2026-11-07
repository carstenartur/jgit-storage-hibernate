"""Exercise history scroll evidence validation with synthetic CSV artifacts."""

import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("summarize-history-scroll-export.py")
SPEC = importlib.util.spec_from_file_location("scroll_evidence", SCRIPT)
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)

DOCUMENTS = 20_000
CHUNK = 100
# Hand-selected result counts and expected windows, including both sides of the guard.
FIXTURE = {
    "repository": ("orm", 20_000, (0, 100, 2000, 9900, 10_000, 10_100, 19_900)),
    "indexed-export": ("search", 20_000, (0, 100, 2000, 9900, 10_000, 10_100, 19_900)),
    "path": ("search", 10_000, (0, 100, 1000, 5000, 9900)),
    "author": ("orm", 6500, (0, 100, 600, 3200, 6400)),
    "time": ("orm", 5000, (0, 100, 500, 2500, 4900)),
    "combined": ("search", 800, (0, 100, 400, 700)),
    "rare": ("search", 20, (0,)),
    "empty": ("search", 0, (0,)),
}
TIMINGS = (90, 30, 45)


def write_csv(path, rows):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)


def fixture(repetitions):
    exports, offsets = [], []
    for scenario, (engine, count, windows) in FIXTURE.items():
        jdbc = int(engine == "orm")
        for repeat in range(1, repetitions + 1):
            timing = TIMINGS[repeat - 1]
            coordinate = dict(
                engine=engine,
                scenario=scenario,
                cache="warm",
                documents=DOCUMENTS,
                chunk_size=CHUNK,
                repeat=repeat,
            )
            export = dict(
                **coordinate,
                rows=count,
                chunks=(count + 99) // 100,
                max_chunk_rows=min(count, CHUNK),
                chunk_p50_upper_ns=1 if count else 0,
                chunk_p95_upper_ns=2 if count else 0,
                chunk_p99_upper_ns=3 if count else 0,
                first_chunk_ns=1_000_000 if count else 0,
                api_total_ns=800_000_000,
                wall_total_ns=1_000_000_000,
                rows_per_second=float(count),
                gc_count=2,
                gc_ms=4,
                heap_before_gc_bytes=32_000_000,
                heap_observed_max_bytes=64_000_000,
                heap_after_bytes=48_000_000,
                heap_after_gc_bytes=32_000_000,
                sessions_before=0,
                sessions_during=1,
                sessions_after=0,
                jdbc_before=0,
                jdbc_during=jdbc,
                jdbc_after=0,
                transactions_completed=jdbc,
                transactions_before=100 + repeat,
                transactions_during=100 + repeat,
                transactions_after=100 + repeat + jdbc,
            )
            exports.append(export)
            if repeat == 1 and scenario in ("repository", "indexed-export"):
                exports.append(dict(export, cache="reopened"))
            for offset in windows:
                size = min(CHUNK, count - offset)
                offsets.append(dict(
                    **coordinate, offset=offset, rows=size,
                    offset_page_ns=timing + offset,
                    scroll_chunk_ns=timing // 3,
                    scroll_prefix_ns=timing * 2 + offset,
                    first_object_id=f"{offset:040x}" if size else "",
                    last_object_id=f"{offset + size - 1:040x}" if size else "",
                ))
    return exports, offsets


class HistoryScrollEvidenceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def validate(self, exports, offsets, repetitions=1):
        write_csv(self.root / "exports.csv", exports)
        write_csv(self.root / "offsets.csv", offsets)
        return TOOL.validate(self.root, DOCUMENTS, [CHUNK], repetitions)

    def test_valid_one_and_three_trial_cells_include_empty_result(self):
        for repetitions, median in ((1, 90), (3, 45)):
            with self.subTest(repetitions=repetitions):
                summary = self.validate(*fixture(repetitions), repetitions)
                self.assertEqual(35, len(summary))
                empty = [row for row in summary if row["scenario"] == "empty"]
                self.assertEqual(
                    [(0, 0, median)],
                    [(r["offset"], r["rows"], r["median_offset_page_ns"]) for r in empty],
                )
                deep = next(
                    r for r in summary
                    if r["scenario"] == "repository" and r["offset"] == 10_100
                )
                self.assertEqual(median + 10_100, deep["median_offset_page_ns"])

    def test_missing_export_measurements_are_rejected(self):
        for field in (
            "first_chunk_ns", "api_total_ns", "wall_total_ns", "rows_per_second",
            "gc_count", "gc_ms", "heap_before_gc_bytes", "heap_observed_max_bytes",
            "heap_after_bytes", "heap_after_gc_bytes", "transactions_before",
            "transactions_during", "transactions_after",
        ):
            with self.subTest(field=field):
                exports, offsets = fixture(1)
                for row in exports:
                    row.pop(field)
                with self.assertRaisesRegex(ValueError, "exports.csv"):
                    self.validate(exports, offsets)

    def test_malformed_csv_shape_is_rejected(self):
        for filename in ("exports.csv", "offsets.csv"):
            for defect in ("duplicate_header", "unexpected_header", "extra_cell", "missing_cell"):
                with self.subTest(filename=filename, defect=defect):
                    self.validate(*fixture(1))
                    path = self.root / filename
                    with path.open(newline="", encoding="utf-8") as stream:
                        content = list(csv.reader(stream))
                    if defect in ("duplicate_header", "unexpected_header"):
                        content[0].append(content[0][0] if defect == "duplicate_header" else "unexpected")
                        for row in content[1:]:
                            row.append(row[0])
                    elif defect == "extra_cell":
                        content[1].append("unexpected")
                    else:
                        content[1].pop()
                    with path.open("w", newline="", encoding="utf-8") as stream:
                        csv.writer(stream).writerows(content)
                    with self.assertRaisesRegex(ValueError, filename):
                        TOOL.validate(self.root, DOCUMENTS, [CHUNK], 1)

    def test_invalid_export_measurements_are_rejected(self):
        for field, value in (
            ("first_chunk_ns", -1), ("api_total_ns", -1), ("api_total_ns", 0),
            ("wall_total_ns", -1), ("wall_total_ns", 0),
            ("rows_per_second", -1), ("rows_per_second", "nan"),
            ("rows_per_second", "inf"), ("rows_per_second", "-inf"),
            ("rows_per_second", "not-a-number"),
            ("gc_count", -1), ("gc_ms", -1), ("gc_ms", "1.5"),
            ("heap_before_gc_bytes", -1), ("heap_observed_max_bytes", -1),
            ("heap_after_bytes", -1), ("heap_after_gc_bytes", -1),
            ("heap_after_gc_bytes", "not-an-integer"),
        ):
            with self.subTest(field=field, value=value):
                exports, offsets = fixture(1)
                exports[0][field] = value
                with self.assertRaises(ValueError):
                    self.validate(exports, offsets)

    def test_inconsistent_export_measurements_are_rejected(self):
        for changes in (
            {"first_chunk_ns": 800_000_001},
            {"api_total_ns": 1_000_000_001},
            {"rows_per_second": 100},
            {"heap_before_gc_bytes": 64_000_001},
            {"heap_after_bytes": 64_000_001},
        ):
            with self.subTest(changes=changes):
                exports, offsets = fixture(1)
                exports[0].update(changes)
                with self.assertRaises(ValueError):
                    self.validate(exports, offsets)

    def test_empty_results_have_no_nonempty_chunk_measurements(self):
        for changes in (
            {"first_chunk_ns": 1},
            {"rows_per_second": 1},
            {"chunk_p95_upper_ns": 1, "chunk_p99_upper_ns": 1},
        ):
            with self.subTest(changes=changes):
                exports, offsets = fixture(1)
                next(row for row in exports if row["scenario"] == "empty").update(changes)
                with self.assertRaises(ValueError):
                    self.validate(exports, offsets)

    def test_transaction_counters_describe_the_cursor_lifetime(self):
        for changes in (
            {"transactions_before": -1},
            {"transactions_during": 102},
            {"transactions_after": 101},
            {"transactions_after": 103},
        ):
            with self.subTest(changes=changes):
                exports, offsets = fixture(1)
                exports[0].update(changes)
                with self.assertRaises(ValueError):
                    self.validate(exports, offsets)

    def test_leaked_resources_are_rejected(self):
        for field in ("sessions_after", "jdbc_after"):
            with self.subTest(field=field):
                exports, offsets = fixture(1)
                exports[0][field] = 1
                with self.assertRaisesRegex(ValueError, "Resource budget exceeded"):
                    self.validate(exports, offsets)

    def test_duplicate_trials_are_rejected(self):
        exports, offsets = fixture(3)
        exports.append(exports[0].copy())
        with self.assertRaisesRegex(ValueError, "Duplicate export"):
            self.validate(exports, offsets, 3)

    def test_duplicate_offset_window_is_rejected(self):
        exports, offsets = fixture(1)
        offsets.append(offsets[0].copy())
        with self.assertRaisesRegex(ValueError, "Duplicate offset"):
            self.validate(exports, offsets)

    def test_missing_window_for_one_trial_is_rejected(self):
        exports, offsets = fixture(3)
        offsets = [
            row for row in offsets
            if (row["scenario"], row["repeat"], row["offset"]) != ("repository", 2, 10_100)
        ]
        with self.assertRaisesRegex(ValueError, "Wrong sampled offsets"):
            self.validate(exports, offsets, 3)


if __name__ == "__main__":
    unittest.main()
