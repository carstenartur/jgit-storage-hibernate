#!/usr/bin/env python3
"""Only the reviewed core class family may differ between comparison JARs."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "prepare_reftable_comparison", Path(__file__).with_name("prepare-publication-reftable-comparison.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReftableJarPreparationTest(unittest.TestCase):
    def test_only_reviewed_class_family_and_recorded_build_metadata_may_differ(self):
        primary = MODULE.CORE_CLASS
        nested = primary.removesuffix(".class") + "$AlignedDfsOutputStream.class"
        old = {primary: "a", nested: "b", "unrelated.class": "c", "META-INF/MANIFEST.MF": "m"}
        new = dict(old, **{primary: "d", nested: "e", "META-INF/MANIFEST.MF": "n"})
        result = MODULE.validate_jar_difference(old, new)
        self.assertEqual(result["classEntries"], sorted([primary, nested]))
        self.assertEqual(result["metadataEntries"], ["META-INF/MANIFEST.MF"])

    def test_unrelated_class_resource_or_inventory_change_is_rejected(self):
        old = {MODULE.CORE_CLASS: "a", "unrelated.class": "b", "schema.sql": "c"}
        for changed in ({"unrelated.class": "z"}, {"schema.sql": "z"}, {"extra.class": "z"}):
            with self.subTest(changed=changed):
                with self.assertRaises(ValueError):
                    MODULE.validate_jar_difference(old, dict(old, **{MODULE.CORE_CLASS: "x"}, **changed))
        with self.assertRaises(ValueError):
            MODULE.validate_jar_difference(old, {MODULE.CORE_CLASS: "x"})

    def test_identical_primary_class_cannot_be_labeled_two_implementations(self):
        with self.assertRaisesRegex(ValueError, "primary"):
            MODULE.validate_jar_difference({MODULE.CORE_CLASS: "a"}, {MODULE.CORE_CLASS: "a"})

    def test_duplicate_jar_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "duplicate.jar"
            with self.assertWarns(UserWarning), zipfile.ZipFile(path, "w") as archive:
                archive.writestr(MODULE.CORE_CLASS, "a")
                archive.writestr(MODULE.CORE_CLASS, "b")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                MODULE.jar_entries(path)

    def test_jar_hashes_come_from_actual_entry_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "core.jar"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(MODULE.CORE_CLASS, b"actual bytecode")
            entries = MODULE.jar_entries(path)
            self.assertEqual(entries[MODULE.CORE_CLASS], MODULE.sha(b"actual bytecode"))


if __name__ == "__main__":
    unittest.main()
