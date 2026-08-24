#!/usr/bin/env python3
"""Static contract tests for versioned GHCR server-image publication."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).parents[1] / "workflows" / "server-image-publish.yml"
FULL_SHA_ACTION = re.compile(
    r"uses:\s+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([0-9a-f]{40})(?:\s+#.*)?$",
    re.MULTILINE,
)


class ServerImagePublishWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    def test_only_immutable_release_sources_can_be_published(self) -> None:
        self.assertIn("tags:\n      - 'v*.*.*'", self.text)
        self.assertIn("branches:\n      - 'image-backfill/v*'", self.text)
        self.assertIn("workflow_dispatch:", self.text)
        self.assertIn("^v[0-9]+\\.[0-9]+\\.[0-9]+$", self.text)
        self.assertIn("ref: ${{ steps.target.outputs.tag }}", self.text)
        self.assertIn("Tag version %s does not match Maven reactor version %s", self.text)
        self.assertIn("Refusing to publish a mutable snapshot image", self.text)

    def test_registry_write_is_narrow_and_non_cancellable(self) -> None:
        self.assertRegex(self.text, r"(?m)^\s+packages:\s+write$")
        self.assertRegex(self.text, r"(?m)^\s+contents:\s+read$")
        self.assertIn("cancel-in-progress: false", self.text)
        self.assertNotIn("contents: write", self.text)

    def test_release_tags_and_oci_evidence_are_published(self) -> None:
        for fragment in (
            'printf \'%s:%s\\n\' "$IMAGE_NAME" "$version"',
            'printf \'%s:latest\\n\' "$IMAGE_NAME"',
            'printf \'%s:edge\\n\' "$IMAGE_NAME"',
            "platforms: linux/amd64,linux/arm64",
            "push: true",
            "provenance: mode=max",
            "sbom: true",
            "org.opencontainers.image.source=",
            "org.opencontainers.image.revision=",
            "org.opencontainers.image.version=",
            "org.opencontainers.image.licenses=BSD-3-Clause",
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

    def test_published_manifest_must_contain_both_supported_architectures(self) -> None:
        self.assertIn("docker buildx imagetools inspect", self.text)
        self.assertIn("grep -q 'linux/amd64'", self.text)
        self.assertIn("grep -q 'linux/arm64'", self.text)


if __name__ == "__main__":
    unittest.main()
