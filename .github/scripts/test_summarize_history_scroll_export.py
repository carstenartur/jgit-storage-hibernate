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
                sessions_before=0,
                sessions_during=1,
                sessions_after=0,
                jdbc_before=0,
                jdbc_during=jdbc,
                jdbc_after=0,
                transactions_completed=jdbc,
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
