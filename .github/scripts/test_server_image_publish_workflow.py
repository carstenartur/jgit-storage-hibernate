#!/usr/bin/env python3
"""Static contract tests for safe, reproducible GHCR server-image publication."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/server-image-publish.yml"
CONTRACT_WORKFLOW = ROOT / ".github/workflows/server-image-publish-contract.yml"
COMPOSE = ROOT / "compose.yaml"
COMPOSE_BUILD = ROOT / "compose.build.yaml"
DOCKERFILE = ROOT / "jgit-storage-hibernate-server/Dockerfile"
DOCKERIGNORE = ROOT / ".dockerignore"
ENV_EXAMPLE = ROOT / ".env.example"
GITIGNORE = ROOT / ".gitignore"
APPLICATION = ROOT / "jgit-storage-hibernate-server/src/main/resources/application.yml"
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
        cls.compose_build = COMPOSE_BUILD.read_text(encoding="utf-8")
        cls.dockerfile = DOCKERFILE.read_text(encoding="utf-8")
        cls.dockerignore = DOCKERIGNORE.read_text(encoding="utf-8")
        cls.env_example = ENV_EXAMPLE.read_text(encoding="utf-8")
        cls.gitignore = GITIGNORE.read_text(encoding="utf-8")
        cls.application = APPLICATION.read_text(encoding="utf-8")
        cls.server_readme = SERVER_README.read_text(encoding="utf-8")
        cls.testcontainers_readme = TESTCONTAINERS_README.read_text(
            encoding="utf-8"
        )
        cls.container = TESTCONTAINERS_CONTAINER.read_text(encoding="utf-8")
        cls.release = DOCUMENTED_RELEASE.read_text(encoding="utf-8").strip()

    def test_only_immutable_release_sources_can_be_published(self) -> None:
        self.assertIn("tags:\n      - 'v*.*.*'", self.text)
        self.assertNotIn("image-backfill", self.text)
        self.assertIn("workflow_dispatch:", self.text)
        self.assertIn("update_aliases:", self.text)
        self.assertIn("default: false", self.text)
        self.assertIn("Manual publication must run from refs/heads/main", self.text)
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

    def test_numeric_version_tag_is_first_write_immutable(self) -> None:
        for fragment in (
            "Reuse an existing immutable version tag",
            "Refusing to overwrite %s",
            "org.opencontainers.image.revision",
            "org.opencontainers.image.version",
            "steps.existing.outputs.exists != 'true'",
            "Build and publish the version tag once",
            "tags: ${{ env.IMAGE_NAME }}:${{ steps.target.outputs.version }}",
            "steps.build.outputs.digest",
            "Reusing immutable image",
        ):
            self.assertIn(fragment, self.text)
        self.assertNotIn(
            "tags: ${{ steps.target.outputs.tags }}",
            self.text,
        )
        self.assertNotIn("printf '%s:latest", self.text)

    def test_aliases_are_explicit_digest_copies_and_cannot_roll_back(self) -> None:
        for fragment in (
            "steps.target.outputs.update_aliases == 'true'",
            "Move compatibility aliases without allowing rollback",
            "Refusing to move latest/edge backwards",
            "sort -V",
            "docker buildx imagetools create",
            '--tag "$IMAGE_NAME:latest"',
            '--tag "$IMAGE_NAME:edge"',
            '"$IMAGE_NAME@$DIGEST"',
        ):
            self.assertIn(fragment, self.text)

    def test_release_tags_and_oci_evidence_are_published(self) -> None:
        for fragment in (
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

    def test_distribution_is_verified_anonymously_by_digest(self) -> None:
        self.assertIn("docker logout ghcr.io", self.text)
        self.assertIn('anonymous_config="$(mktemp -d)"', self.text)
        self.assertIn('tags=("$VERSION")', self.text)
        self.assertIn('tags+=(latest edge)', self.text)
        self.assertIn('docker --config "$anonymous_config"', self.text)
        self.assertIn("for attempt in $(seq 1 12)", self.text)
        self.assertIn('observed_digest="$(awk', self.text)
        self.assertIn('"$observed_digest" != "$DIGEST"', self.text)
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

    def test_compose_requires_secrets_and_has_safe_container_defaults(self) -> None:
        self.assertGreaterEqual(self.compose.count("${JSH_DATABASE_PASSWORD:?"), 2)
        self.assertIn("${JSH_ADMIN_PASSWORD:?", self.compose)
        self.assertIn("${JSH_BIND_ADDRESS:-127.0.0.1}", self.compose)
        for fragment in (
            "read_only: true",
            "init: true",
            "/tmp:rw,noexec,nosuid,size=256m,mode=1777",
            "cap_drop:\n      - ALL",
            "no-new-privileges:true",
        ):
            self.assertIn(fragment, self.compose)
        self.assertNotIn("change-me", self.compose)
        self.assertNotIn("build:", self.compose)

    def test_source_build_is_an_explicit_local_override(self) -> None:
        self.assertIn("image: jgit-storage-hibernate-server:local", self.compose_build)
        self.assertIn("build:", self.compose_build)
        self.assertIn("context: .", self.compose_build)
        self.assertIn(
            "dockerfile: jgit-storage-hibernate-server/Dockerfile",
            self.compose_build,
        )
        self.assertIn("compose.build.yaml", self.server_readme)

    def test_build_context_and_runtime_are_hardened(self) -> None:
        for fragment in (".git", ".env", "**/target/", ".idea"):
            self.assertIn(fragment, self.dockerignore)
        self.assertIn("# syntax=docker/dockerfile:1.7", self.dockerfile)
        self.assertIn(
            "--mount=type=cache,target=/root/.m2,sharing=locked",
            self.dockerfile,
        )
        self.assertIn("--shell /usr/sbin/nologin", self.dockerfile)
        self.assertIn("USER jgit", self.dockerfile)
        self.assertIn("HEALTHCHECK", self.dockerfile)

    def test_local_secret_template_is_safe_and_ignored(self) -> None:
        self.assertIn("JSH_DATABASE_PASSWORD=", self.env_example)
        self.assertIn("JSH_ADMIN_PASSWORD=", self.env_example)
        self.assertNotIn("change-me", self.env_example)
        self.assertIn("JSH_BIND_ADDRESS=127.0.0.1", self.env_example)
        self.assertIn("\n.env\n", self.gitignore)
        self.assertIn("!.env.example", self.gitignore)

    def test_management_and_proxy_trust_are_explicit(self) -> None:
        self.assertIn(
            "forward-headers-strategy: ${JSH_FORWARD_HEADERS_STRATEGY:NONE}",
            self.application,
        )
        self.assertIn(
            "include: ${JSH_MANAGEMENT_ENDPOINTS:health,info}",
            self.application,
        )
        self.assertNotIn("include: health,info,metrics", self.application)

    def test_documentation_states_the_real_compatibility_boundary(self) -> None:
        self.assertIn(
            "Git protocol compatible**, not **forge- or plug-in compatible",
            self.server_readme,
        )
        self.assertIn("GitLab, Gitea, Gerrit", self.server_readme)
        self.assertIn("do not embed a password", self.server_readme)
        self.assertNotIn(
            "http://$JSH_ADMIN_USERNAME:$JSH_ADMIN_PASSWORD@",
            self.server_readme,
        )

    def test_contract_workflow_executes_tests_and_both_compose_parsers(self) -> None:
        self.assertIn("workflow_dispatch:", self.contract)
        self.assertIn(
            "python3 .github/scripts/test_server_image_publish_workflow.py",
            self.contract,
        )
        self.assertIn("JSH_DATABASE_PASSWORD: contract-database-password", self.contract)
        self.assertIn("JSH_ADMIN_PASSWORD: contract-admin-password", self.contract)
        self.assertIn("docker compose -f compose.yaml config --quiet", self.contract)
        self.assertIn(
            "docker compose -f compose.yaml -f compose.build.yaml config --quiet",
            self.contract,
        )
        actions = FULL_SHA_ACTION.findall(self.contract)
        self.assertEqual({"actions/checkout"}, {name for name, _ in actions})
        self.assertNotRegex(self.contract, r"uses:\s+[^\s]+@v[0-9]")


if __name__ == "__main__":
    unittest.main()
