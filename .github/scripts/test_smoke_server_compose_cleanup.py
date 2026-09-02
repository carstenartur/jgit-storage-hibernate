#!/usr/bin/env python3
"""Regression contract for publication-smoke cleanup environment propagation."""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
SMOKE_SCRIPT = ROOT / ".github/scripts/smoke_server_compose.sh"


class PublicationSmokeCleanupContractTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = SMOKE_SCRIPT.read_text(encoding="utf-8")

    def test_nested_publication_smoke_exports_required_compose_inputs(self) -> None:
        for fragment in (
            "persist_publication_cleanup_environment()",
            '[[ "${GITHUB_ACTIONS:-}" == true ]] || return 0',
            '[[ -n "${GITHUB_WORKSPACE:-}" ]] || return 0',
            '[[ -n "${GITHUB_ENV:-}" ]] || return 0',
            '[[ "$PWD" == "$GITHUB_WORKSPACE/publication-tooling" ]] || return 0',
            "JSH_ADMIN_USERNAME \\\n      JSH_ADMIN_PASSWORD \\\n      JSH_DATABASE_PASSWORD \\\n      JSH_SERVER_IMAGE",
            "value=${!variable-}",
            "must be one line",
            "printf '%s=%s\\n' \"$variable\" \"$value\" >> \"$GITHUB_ENV\"",
        ):
            self.assertIn(fragment, self.text)

    def test_environment_is_persisted_before_any_compose_failure_path(self) -> None:
        invocation = "\npersist_publication_cleanup_environment\n\nwork="
        self.assertIn(invocation, self.text)
        self.assertLess(
            self.text.index(invocation),
            self.text.index("wait_until_ready()"),
        )

    def test_local_and_normal_smoke_invocations_are_not_modified(self) -> None:
        directory_guard = (
            '[[ "$PWD" == "$GITHUB_WORKSPACE/publication-tooling" ]] || return 0'
        )
        self.assertEqual(1, self.text.count(directory_guard))
        self.assertNotIn(".env", self.text)


if __name__ == "__main__":
    unittest.main()
