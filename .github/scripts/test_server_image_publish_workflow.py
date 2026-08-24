#!/usr/bin/env python3
"""Static contract tests for versioned GHCR server-image publication."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/server-image-publish.yml"
CONTRACT_WORKFLOW = ROOT / ".github/workflows/server-image-publish-contract.yml"
COMPOSE = ROOT / "compose.yaml"
DOCKERFILE = ROOT / "jgit-storage-hibernate-server/Dockerfile"
SERVER_README = ROOT / "jgit-storage-hibernate-server/README.md"
TESTCONTAINERS_README = ROOT / "jgit-storage-hibernate-testcontainers/README.md"
TESTCONTAINERS_CONTAINER = (
    ROOT
    / "jgit-storage-hibernate-testcontainers/src/main/java/"
    "io/github/carstenartur/jgit/storage/hibernate/testcontainers/"
    "JgitStorageContainer.java"
)
DOCUMENTED_RELEASE = ROOT / "docs/current-release-version.txt"
IMAGE = "ghcr.io/carstenartur/jgit-storage-hibernate-server"
FULL_SHA_ACTION = re.compile(
    r"uses:\s+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([0-9a-f]{40})(?:\s+#.*)?$",
    re.MULTILINE,
)


class ServerImagePublishWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.contract = CONTRACT_WORKFLOW.read_text(encoding="utf-8")
        cls.compose = COMPOSE.read_text(encoding="utf-8")
        cls.dockerfile = DOCKERFILE.read_text(encoding="utf-8")
        cls.server_readme = SERVER_README.read_text(encoding="utf-8")
        cls.testcontainers_readme = TESTCONTAINERS_README.read_text(
            encoding="utf-8"
        )
        cls.container = TESTCONTAINERS_CONTAINER.read_text(encoding="utf-8")
        cls.release = DOCUMENTED_RELEASE.read_text(encoding="utf-8").strip()

    def test_only_immutable_release_sources_can_be_published(self) -> None:
        self.assertIn("tags:\n      - 'v*.*.*'", self.text)
        self.assertIn("branches:\n      - 'image-backfill/v*'", self.text)
        self.assertIn("workflow_dispatch:", self.text)
        self.assertIn(r"^v[0-9]+\.[0-9]+\.[0-9]+$", self.text)
        self.assertIn("ref: ${{ steps.target.outputs.tag }}", self.text)
        self.assertIn("fetch-depth: 0", self.text)
        self.assertIn("git cat-file -t", self.text)
        self.assertIn("must be an annotated immutable tag", self.text)
        self.assertIn(
            "Tag version %s does not match Maven reactor version %s", self.text
        )
        self.assertIn("Refusing to publish a mutable snapshot image", self.text)

    def test_registry_write_is_narrow_and_globally_serialized(self) -> None:
        self.assertRegex(self.text, r"(?m)^\s+packages:\s+write$")
        self.assertRegex(self.text, r"(?m)^\s+contents:\s+read$")
        self.assertIn(
            "group: publish-server-image-${{ github.repository }}", self.text
        )
        self.assertIn("cancel-in-progress: false", self.text)
        self.assertNotIn("contents: write", self.text)

    def test_release_tags_and_oci_evidence_are_published(self) -> None:
        for fragment in (
            "printf '%s:%s\\n' \"$IMAGE_NAME\" \"$version\"",
            "printf '%s:latest\\n' \"$IMAGE_NAME\"",
            "printf '%s:edge\\n' \"$IMAGE_NAME\"",
            "platforms: linux/amd64,linux/arm64",
            "push: true",
            "provenance: mode=max",
            "sbom: true",
            "org.opencontainers.image.source=",
            "org.opencontainers.image.revision=",
            "org.opencontainers.image.version=",
            "org.opencontainers.image.licenses=BSD-3-Clause",
            "steps.build.outputs.digest",
        ):
            self.assertIn(fragment, self.text)

    def test_every_third_party_action_is_pinned_to_a_full_commit(self) -> None:
        actions = FULL_SHA_ACTION.findall(self.text)
        self.assertEqual(
            {
                "actions/checkout",
                "actions/setup-java",
                "docker/setup-qemu-action",
                "docker/setup-buildx-action",
                "docker/login-action",
                "docker/build-push-action",
            },
            {name for name, _ in actions},
        )
        self.assertNotRegex(self.text, r"uses:\s+[^\s]+@v[0-9]")

    def test_distribution_is_verified_without_registry_credentials(self) -> None:
        self.assertIn("docker logout ghcr.io", self.text)
        self.assertIn('anonymous_config="$(mktemp -d)"', self.text)
        self.assertIn('for tag in "$VERSION" latest edge', self.text)
        self.assertIn('docker --config "$anonymous_config"', self.text)
        self.assertIn("for attempt in $(seq 1 12)", self.text)
        self.assertIn("grep -q 'linux/amd64'", self.text)
        self.assertIn("grep -q 'linux/arm64'", self.text)
        self.assertIn("Anonymous pull: verified", self.text)

    def test_stable_defaults_match_the_documented_release(self) -> None:
        self.assertRegex(self.release, r"^[0-9]+\.[0-9]+\.[0-9]+$")
        stable_image = f"{IMAGE}:{self.release}"
        self.assertIn(
            f"image: ${{JSH_SERVER_IMAGE:-{stable_image}}}", self.compose
        )
        self.assertIn(
            f'DEFAULT_IMAGE_VERSION = "{self.release}"', self.container
        )
        self.assertIn(stable_image, self.server_readme)
        self.assertIn(stable_image, self.testcontainers_readme)
        self.assertNotIn(f"{IMAGE}:edge", self.compose)
        self.assertNotIn(f"{IMAGE}:edge", self.container)

    def test_multi_architecture_build_does_not_emulate_the_maven_build(self) -> None:
        self.assertIn(
            "FROM --platform=$BUILDPLATFORM "
            "maven:3.9.11-eclipse-temurin-21 AS build",
            self.dockerfile,
        )

    def test_contract_workflow_executes_the_static_test_and_compose_parser(self) -> None:
        self.assertIn("workflow_dispatch:", self.contract)
        self.assertIn(
            "python3 .github/scripts/test_server_image_publish_workflow.py",
            self.contract,
        )
        self.assertIn("docker compose config --quiet", self.contract)
        actions = FULL_SHA_ACTION.findall(self.contract)
        self.assertEqual({"actions/checkout"}, {name for name, _ in actions})
        self.assertNotRegex(self.contract, r"uses:\s+[^\s]+@v[0-9]")


if __name__ == "__main__":
    unittest.main()
