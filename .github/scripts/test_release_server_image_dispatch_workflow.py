#!/usr/bin/env python3
"""Static contracts for reliable release-to-OCI workflow dispatch."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github/workflows/release-server-image-dispatch.yml"
REQUEST = ROOT / ".github/server-image-publication-request.json"
FULL_SHA_ACTION = re.compile(
    r"uses:\s+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([0-9a-f]{40})(?:\s+#.*)?$",
    re.MULTILINE,
)


class ReleaseServerImageDispatchWorkflowTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.request = json.loads(REQUEST.read_text(encoding="utf-8"))

    def test_successful_release_completion_and_reviewed_request_are_triggers(self) -> None:
        self.assertIn("workflow_run:\n    workflows: [ Release ]", self.text)
        self.assertIn("types: [ completed ]", self.text)
        self.assertIn("push:\n    branches: [ main ]", self.text)
        self.assertIn(
            "- '.github/server-image-publication-request.json'", self.text
        )
        self.assertIn("github.event.workflow_run.conclusion == 'success'", self.text)
        self.assertIn("github.event.workflow_run.event == 'pull_request'", self.text)
        self.assertIn(
            "startsWith(github.event.workflow_run.head_branch, 'release/prepare-')",
            self.text,
        )

    def test_release_workflow_must_have_executed_the_real_finalize_job(self) -> None:
        self.assertIn('"gh", "run", "view", run_id, "--json", "jobs"', self.text)
        self.assertIn(
            'job.get("name") == "Publish merged immutable release"', self.text
        )
        self.assertIn('conclusions != ["success"]', self.text)
        self.assertIn("Release workflow did not successfully execute", self.text)

    def test_dispatcher_can_write_actions_but_not_contents_or_packages(self) -> None:
        self.assertRegex(self.text, r"(?m)^  contents:\s+read$")
        self.assertRegex(self.text, r"(?m)^  actions:\s+write$")
        self.assertNotRegex(self.text, r"(?m)^  contents:\s+write$")
        self.assertNotIn("packages: write", self.text)
        self.assertIn("cancel-in-progress: false", self.text)

    def test_request_is_strict_and_records_the_current_recovery(self) -> None:
        self.assertEqual(
            {"release_tag", "update_aliases", "reason"}, set(self.request)
        )
        self.assertEqual("v0.11.3", self.request["release_tag"])
        self.assertIs(self.request["update_aliases"], True)
        self.assertTrue(self.request["reason"].strip())
        self.assertIn(
            'set(request) - {"release_tag", "update_aliases", "reason"}',
            self.text,
        )
        self.assertIn("update_aliases must be a JSON boolean", self.text)
        self.assertIn(r"^v[0-9]+\.[0-9]+\.[0-9]+$", self.text)

    def test_only_a_published_annotated_matching_release_is_dispatched(self) -> None:
        for fragment in (
            'git cat-file -t "refs/tags/$RELEASE_TAG"',
            "must be an annotated immutable tag",
            'git show "refs/tags/$RELEASE_TAG:pom.xml"',
            'gh release view "$RELEASE_TAG"',
            "--json tagName,isDraft,isPrerelease",
            '"$is_draft" != false',
            '"$is_prerelease" != false',
        ):
            self.assertIn(fragment, self.text)

    def test_existing_hardened_publication_workflow_is_dispatched_on_main(self) -> None:
        self.assertIn("gh workflow run server-image-publish.yml", self.text)
        self.assertIn("--ref main", self.text)
        self.assertIn('-f release_tag="$RELEASE_TAG"', self.text)
        self.assertIn('-f update_aliases="$UPDATE_ALIASES"', self.text)
        self.assertNotIn("docker build", self.text)
        self.assertNotIn("docker push", self.text)

    def test_checkout_is_pinned_and_does_not_persist_credentials(self) -> None:
        actions = FULL_SHA_ACTION.findall(self.text)
        self.assertEqual({"actions/checkout"}, {name for name, _ in actions})
        self.assertIn("ref: main", self.text)
        self.assertIn("fetch-depth: 0", self.text)
        self.assertIn("persist-credentials: false", self.text)
        self.assertNotRegex(self.text, r"uses:\s+[^\s]+@v[0-9]")


if __name__ == "__main__":
    unittest.main()
