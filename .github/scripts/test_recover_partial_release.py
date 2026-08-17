#!/usr/bin/env python3
"""Regression tests for safe recovery of a partially published release."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("recover-partial-release.py")
WORKFLOW = Path(__file__).parents[1] / "workflows" / "recover-release.yml"

spec = importlib.util.spec_from_file_location("recover_partial_release", SCRIPT)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Cannot import {SCRIPT}")
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def checksums(path: Path) -> dict[str, str]:
    content = path.read_bytes()
    return {
        name: hashlib.new(name, content).hexdigest()
        for name in recovery.HASH_ALGORITHMS
    }


class RecoverPartialReleaseTest(unittest.TestCase):
    def test_release_lineage_accepts_only_automation_and_metadata(self) -> None:
        accepted = [
            ".github/scripts/recover-partial-release.py",
            ".github/workflows/recover-release.yml",
            ".zenodo.json",
            "CITATION.cff",
            "CITATION.md",
            "README.md",
            "SECURITY.md",
            "codemeta.json",
            "docs/consuming.md",
            "jgit-storage-hibernate-core/README.md",
            "jgit-storage-hibernate-core/pom.xml",
            "pom.xml",
        ]
        rejected = [
            "jgit-storage-hibernate-core/src/main/java/example/Storage.java",
            "jgit-storage-hibernate-core/src/main/resources/example.properties",
            "jgit-storage-hibernate-core/src/test/java/example/StorageTest.java",
            "LICENSE",
            "../pom.xml",
            "/pom.xml",
        ]
        for path in accepted:
            with self.subTest(path=path):
                self.assertTrue(recovery.allowed_lineage_path(path))
        for path in rejected:
            with self.subTest(path=path):
                self.assertFalse(recovery.allowed_lineage_path(path))

    def test_manifest_validation_checks_all_bytes_and_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = (
                root
                / "io/github/carstenartur/example/0.11.0/example-0.11.0.jar"
            )
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"immutable artifact")
            entry = {
                "path": artifact.relative_to(root).as_posix(),
                "size": artifact.stat().st_size,
                **checksums(artifact),
            }
            manifest = root / "releases/0.11.0.json"
            manifest.parent.mkdir()
            manifest.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "groupId": "io.github.carstenartur",
                        "version": "0.11.0",
                        "canonicalChecksums": ["sha256", "sha512"],
                        "compatibilityChecksums": ["sha1"],
                        "files": [entry],
                    }
                ),
                encoding="utf-8",
            )

            validated = recovery.validate_manifest(root, manifest, "0.11.0")
            self.assertEqual([entry], validated)

            artifact.write_bytes(b"modified artifact")
            with self.assertRaisesRegex(recovery.RecoveryError, "Size mismatch"):
                recovery.validate_manifest(root, manifest, "0.11.0")

    def test_manifest_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "groupId": "io.github.carstenartur",
                        "version": "0.11.0",
                        "canonicalChecksums": ["sha256", "sha512"],
                        "compatibilityChecksums": ["sha1"],
                        "files": [
                            {
                                "path": "../0.11.0/example.jar",
                                "size": 0,
                                "sha1": "0" * 40,
                                "sha256": "0" * 64,
                                "sha512": "0" * 128,
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(recovery.RecoveryError, "Unsafe manifest"):
                recovery.validate_manifest(root, manifest, "0.11.0")

    def test_manifest_requires_both_checksum_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = (
                root
                / "io/github/carstenartur/example/0.11.0/example-0.11.0.jar"
            )
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"immutable artifact")
            entry = {
                "path": artifact.relative_to(root).as_posix(),
                "size": artifact.stat().st_size,
                **checksums(artifact),
            }
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "groupId": "io.github.carstenartur",
                        "version": "0.11.0",
                        "canonicalChecksums": ["sha256", "sha512"],
                        "files": [entry],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                recovery.RecoveryError, "missing required field"
            ):
                recovery.validate_manifest(root, manifest, "0.11.0")

    def test_manifest_rejects_short_non_maven_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "artifact/0.11.0/example.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"immutable artifact")
            entry = {
                "path": artifact.relative_to(root).as_posix(),
                "size": artifact.stat().st_size,
                **checksums(artifact),
            }
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "groupId": "io.github.carstenartur",
                        "version": "0.11.0",
                        "canonicalChecksums": ["sha256", "sha512"],
                        "compatibilityChecksums": ["sha1"],
                        "files": [entry],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                recovery.RecoveryError, "does not match"
            ):
                recovery.validate_manifest(root, manifest, "0.11.0")

    def test_source_lineage_requires_real_ancestry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(
                ["git", "config", "user.name", "Test"], cwd=repository, check=True
            )
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=repository,
                check=True,
            )
            (repository / "README.md").write_text("published\n", encoding="utf-8")
            subprocess.run(["git", "add", "README.md"], cwd=repository, check=True)
            subprocess.run(
                ["git", "commit", "-q", "-m", "published"],
                cwd=repository,
                check=True,
            )
            published = subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=repository, text=True
            ).strip()

            subprocess.run(
                ["git", "switch", "--orphan", "unrelated"],
                cwd=repository,
                check=True,
                stdout=subprocess.DEVNULL,
            )
            (repository / "README.md").write_text("unrelated\n", encoding="utf-8")
            subprocess.run(["git", "add", "README.md"], cwd=repository, check=True)
            subprocess.run(
                ["git", "commit", "-q", "-m", "unrelated"],
                cwd=repository,
                check=True,
            )
            unrelated = subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=repository, text=True
            ).strip()

            with self.assertRaisesRegex(
                recovery.RecoveryError, "is not an ancestor"
            ):
                recovery.validate_source_lineage(
                    repository, published, unrelated
                )

    def test_recovery_workflow_uses_a_repository_owned_marker(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("- 'release-recovery/**'", text)
        self.assertIn("- '.github/release-recovery.json'", text)
        self.assertIn("ref: main", text)
        self.assertIn("group: jgit-storage-hibernate-release", text)
        self.assertIn("contents: write", text)
        self.assertNotIn("pull-requests: write", text)
        self.assertNotIn(
            "carstenartur/jgit-storage-hibernate/maven-repository", text
        )
        self.assertIn("mvn -B verify", text)
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('required_environment("GITHUB_REPOSITORY")', script)
        self.assertIn("recover-partial-release.py", text)

    def test_recovery_never_pushes_directly_to_main(self) -> None:
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("HEAD:main", text)
        self.assertNotIn("HEAD:refs/heads/main", text)
        self.assertIn("release/next-", text)


if __name__ == "__main__":
    unittest.main()
