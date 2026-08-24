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
                    Image: ghcr.io/carstenartur/jgit-storage-hibernate-server:0.1.5
                    Historical migration baseline: 0.1.4
                    """
                ).lstrip(),
            )
            self.write(
                root / "docs/guide.md",
                """The documented release line is **0.1.5**.

<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
  <version>0.1.5</version>
</dependency>

The legacy migration baseline remains 0.1.4.
""",
            )
            self.write(
                root / "docs/releases/0.1.5.md",
                "The documented release line is **0.1.5**.\n"
                "io.github.carstenartur:jgit-storage-hibernate-core:0.1.5\n"
                "ghcr.io/carstenartur/jgit-storage-hibernate-server:0.1.5\n",
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
            self.assertIn(
                "ghcr.io/carstenartur/jgit-storage-hibernate-server:0.1.6",
                readme,
            )
            self.assertIn("<groupId>com.example</groupId>", readme)
            self.assertIn("<version>X.Y.Z</version>", readme)
            self.assertIn("Historical migration baseline: 0.1.4", readme)
            self.assertIn("The documented release line is **0.1.6**.", guide)
            self.assertIn("<version>0.1.6</version>", guide)
            self.assertIn("legacy migration baseline remains 0.1.4", guide)
            self.assertIn("<version>0.1.6</version>", module_readme)
            self.assertEqual(
                "The documented release line is **0.1.5**.\n"
                "io.github.carstenartur:jgit-storage-hibernate-core:0.1.5\n"
                "ghcr.io/carstenartur/jgit-storage-hibernate-server:0.1.5\n",
                release_note,
            )

    def test_release_advances_the_supported_security_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(
                root / "SECURITY.md",
                """# Security Policy

Security fixes are provided for the latest released `0.1.x` version.

| Version | Supported |
|---|---|
| Latest released `0.1.x` | Yes |
| Older `0.1.x` releases | Upgrade required |
| `0.1.x-SNAPSHOT` builds | No security support guarantee |
""",
            )

            previous_directory = Path.cwd()
            os.chdir(root)
            try:
                UPDATE_RELEASE_METADATA.update_security_policy("0.9.0")
            finally:
                os.chdir(previous_directory)

            policy = (root / "SECURITY.md").read_text(encoding="utf-8")
            self.assertNotIn("0.1.x", policy)
            self.assertEqual(4, policy.count("0.9.x"))
            self.assertIn("`0.9.x-SNAPSHOT`", policy)

    def test_release_advances_stable_server_image_defaults(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(
                root / "compose.yaml",
                "image: ${JSH_SERVER_IMAGE:-"
                "ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2}\n",
            )
            container = (
                root
                / "jgit-storage-hibernate-testcontainers/src/main/java/"
                "io/github/carstenartur/jgit/storage/hibernate/testcontainers/"
                "JgitStorageContainer.java"
            )
            self.write(
                container,
                'public static final String DEFAULT_IMAGE_VERSION = "0.11.2";\n',
            )

            previous_directory = Path.cwd()
            os.chdir(root)
            try:
                changed = UPDATE_RELEASE_METADATA.update_server_image_defaults(
                    "0.11.3"
                )
            finally:
                os.chdir(previous_directory)

            self.assertEqual(
                {
                    Path("compose.yaml"),
                    Path(
                        "jgit-storage-hibernate-testcontainers/src/main/java/"
                        "io/github/carstenartur/jgit/storage/hibernate/"
                        "testcontainers/JgitStorageContainer.java"
                    ),
                },
                set(changed),
            )
            self.assertIn(
                "jgit-storage-hibernate-server:0.11.3}",
                (root / "compose.yaml").read_text(encoding="utf-8"),
            )
            self.assertIn(
                'DEFAULT_IMAGE_VERSION = "0.11.3"',
                container.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "update_server_image_defaults(version)",
                SCRIPT.read_text(encoding="utf-8"),
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
                with self.assertRaises(SystemExit):
                    UPDATE_RELEASE_METADATA.update_server_image_defaults(
                        "0.1.6-SNAPSHOT"
                    )
            finally:
                os.chdir(previous_directory)

    def test_snapshot_transition_preserves_public_release_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root / "docs/current-release-version.txt", "0.11.2\n")
            expected = {
                "CITATION.cff": 'version: "0.11.2"\ndate-released: "2026-08-24"\n',
                "CITATION.md": "Version 0.11.2.\ndate = {2026-08-24},\n",
                "codemeta.json": '{"version":"0.11.2","datePublished":"2026-08-24"}\n',
                ".zenodo.json": '{"version":"0.11.2","publication_date":"2026-08-24"}\n',
                "compose.yaml": "image: ${JSH_SERVER_IMAGE:-ghcr.io/carstenartur/"
                "jgit-storage-hibernate-server:0.11.2}\n",
                "jgit-storage-hibernate-testcontainers/src/main/java/"
                "io/github/carstenartur/jgit/storage/hibernate/testcontainers/"
                "JgitStorageContainer.java":
                'public static final String DEFAULT_IMAGE_VERSION = "0.11.2";\n',
            }
            for name, content in expected.items():
                self.write(root / name, content)

            previous_directory = Path.cwd()
            os.chdir(root)
            try:
                UPDATE_RELEASE_METADATA.update_release_metadata(
                    "0.11.3-SNAPSHOT", False, "2099-01-01"
                )
            finally:
                os.chdir(previous_directory)

            for name, content in expected.items():
                self.assertEqual(
                    content,
                    (root / name).read_text(encoding="utf-8"),
                    f"snapshot transition rewrote {name}",
                )

    def test_non_snapshot_development_metadata_is_rejected(self) -> None:
        with self.assertRaises(SystemExit):
            UPDATE_RELEASE_METADATA.update_release_metadata(
                "0.11.3", False, "2099-01-01"
            )

    def test_release_script_generates_documentation_instead_of_requiring_pre_alignment(self) -> None:
        text = RELEASE_SCRIPT.read_text(encoding="utf-8")
        normalized = re.sub(r"[ \t]*\\\n[ \t]*", " ", text)

        self.assertNotIn(
            "Documented release $DOCUMENTED_RELEASE_VERSION does not match requested release",
            text,
        )
        self.assertIn("Automatic release preparation", text)
        self.assertIn(
            "python3 .github/scripts/verify-release-consistency.py",
            text,
        )

        prepare_release = normalized.index("prepare_release()")
        preflight = normalized.index("verify_repository_contract", prepare_release)
        set_release = normalized.index(
            'mvn -B versions:set -DnewVersion="$RELEASE_VERSION"',
            preflight,
        )
        generate_release = normalized.index(
            'python3 .github/scripts/update-release-metadata.py "$RELEASE_VERSION" --release',
            set_release,
        )
        write_candidate = normalized.index("write_release_candidate", generate_release)
        generated_state_check = normalized.index(
            "verify_repository_contract",
            write_candidate,
        )
        stage_generated_release = normalized.index(
            "git add -A",
            generated_state_check,
        )

        self.assertLess(preflight, set_release)
        self.assertLess(set_release, generate_release)
        self.assertLess(generate_release, write_candidate)
        self.assertLess(write_candidate, generated_state_check)
        self.assertLess(generated_state_check, stage_generated_release)
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
