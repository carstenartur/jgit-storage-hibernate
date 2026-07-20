#!/usr/bin/env python3
"""Regression tests for self-contained release documentation preparation."""

from __future__ import annotations

import importlib.util
import os
import re
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("update-release-metadata.py")
RELEASE_SCRIPT = Path(__file__).with_name("release.sh")
SPEC = importlib.util.spec_from_file_location("update_release_metadata", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
UPDATE_RELEASE_METADATA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UPDATE_RELEASE_METADATA)


class UpdateReleaseDocumentationTest(unittest.TestCase):

    def test_release_advances_active_examples_but_preserves_history_and_placeholders(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root / "docs/current-release-version.txt", "0.1.5\n")
            self.write(
                root / "README.md",
                textwrap.dedent(
                    """
                    The documented release line is **0.1.5**.

                    <dependency>
                      <groupId>io.github.carstenartur</groupId>
                      <artifactId>jgit-storage-hibernate-core</artifactId>
                      <version>0.1.5</version>
                    </dependency>

                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>unrelated</artifactId>
                      <version>0.1.5</version>
                    </dependency>

                    Version-neutral example:
                    <dependency>
                      <groupId>io.github.carstenartur</groupId>
                      <artifactId>jgit-storage-hibernate-search</artifactId>
                      <version>X.Y.Z</version>
                    </dependency>

                    Coordinate: io.github.carstenartur:jgit-storage-hibernate-core:0.1.5
                    Historical migration baseline: 0.1.4
                    """
                ).lstrip(),
            )
            self.write(
                root / "docs/guide.md",
                """<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
  <version>0.1.5</version>
</dependency>

The legacy migration baseline remains 0.1.4.
""",
            )
            self.write(
                root / "docs/releases/0.1.5.md",
                "io.github.carstenartur:jgit-storage-hibernate-core:0.1.5\n",
            )
            self.write(
                root / "jgit-storage-hibernate-core/README.md",
                """<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.5</version>
</dependency>
""",
            )

            previous_directory = Path.cwd()
            os.chdir(root)
            try:
                UPDATE_RELEASE_METADATA.update_public_documentation("0.1.6")
            finally:
                os.chdir(previous_directory)

            readme = (root / "README.md").read_text(encoding="utf-8")
            guide = (root / "docs/guide.md").read_text(encoding="utf-8")
            module_readme = (
                root / "jgit-storage-hibernate-core/README.md"
            ).read_text(encoding="utf-8")
            release_note = (root / "docs/releases/0.1.5.md").read_text(encoding="utf-8")

            self.assertEqual(
                "0.1.6\n",
                (root / "docs/current-release-version.txt").read_text(encoding="utf-8"),
            )
            self.assertIn("The documented release line is **0.1.6**.", readme)
            self.assertIn("<version>0.1.6</version>", readme)
            self.assertIn(
                "io.github.carstenartur:jgit-storage-hibernate-core:0.1.6",
                readme,
            )
            self.assertIn("<groupId>com.example</groupId>", readme)
            self.assertIn("<version>X.Y.Z</version>", readme)
            self.assertIn("Historical migration baseline: 0.1.4", readme)
            self.assertIn("<version>0.1.6</version>", guide)
            self.assertIn("legacy migration baseline remains 0.1.4", guide)
            self.assertIn("<version>0.1.6</version>", module_readme)
            self.assertEqual(
                "io.github.carstenartur:jgit-storage-hibernate-core:0.1.5\n",
                release_note,
            )

    def test_snapshot_is_rejected_as_public_release_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root / "docs/current-release-version.txt", "0.1.5\n")
            previous_directory = Path.cwd()
            os.chdir(root)
            try:
                with self.assertRaises(SystemExit):
                    UPDATE_RELEASE_METADATA.update_public_documentation("0.1.6-SNAPSHOT")
            finally:
                os.chdir(previous_directory)

    def test_release_script_generates_documentation_instead_of_requiring_pre_alignment(self) -> None:
        text = RELEASE_SCRIPT.read_text(encoding="utf-8")
        normalized = re.sub(r"\\\s*\n\s*", " ", text)

        self.assertNotIn(
            "Documented release $DOCUMENTED_RELEASE_VERSION does not match requested release",
            text,
        )
        self.assertIn("Automatic release preparation", text)

        preflight = text.index("python3 .github/scripts/verify-release-consistency.py")
        set_release = text.index(
            'mvn -B versions:set -DnewVersion="$RELEASE_VERSION"'
        )
        generate_release = text.index(
            'python3 .github/scripts/update-release-metadata.py "$RELEASE_VERSION" --release'
        )
        generated_state_check = text.index(
            "python3 .github/scripts/verify-release-consistency.py",
            generate_release,
        )

        self.assertLess(preflight, set_release)
        self.assertLess(set_release, generate_release)
        self.assertLess(generate_release, generated_state_check)
        self.assertIn(
            "README.md docs jgit-storage-hibernate-*/README.md",
            normalized,
        )
        self.assertIn(
            'python3 .github/scripts/update-release-metadata.py "$NEXT_VERSION"',
            text,
        )
        self.assertNotIn(
            'python3 .github/scripts/update-release-metadata.py "$NEXT_VERSION" --release',
            text,
        )

    @staticmethod
    def write(path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
