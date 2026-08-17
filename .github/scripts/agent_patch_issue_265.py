#!/usr/bin/env python3
"""Apply the focused implementation for issue #265 on a clean main-based branch."""

from __future__ import annotations

import re
from pathlib import Path


def read(path_name: str) -> str:
    return Path(path_name).read_text(encoding="utf-8")


def write(path_name: str, text: str) -> None:
    Path(path_name).write_text(text, encoding="utf-8")


def replace_once(path_name: str, old: str, new: str) -> None:
    text = read(path_name)
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one occurrence in {path_name}, found {count}: {old!r}"
        )
    write(path_name, text.replace(old, new, 1))


def regex_replace_once(
    path_name: str, pattern: str, replacement: str, *, flags: int = 0
) -> None:
    text = read(path_name)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one regex occurrence in {path_name}, found {count}: {pattern!r}"
        )
    write(path_name, updated)


# A non-dry-run release must have the credential needed for both protected PRs before any
# release side effect can occur. This prevents a green preparation without a PR and prevents
# finalization from publishing artifacts before discovering that the next-development PR cannot
# be opened.
replace_once(
    ".github/scripts/release.sh",
    '''[[ "$RELEASE_ACTION" != finalize || "$DRY_RUN" == false ]] \\
  || fail "Release finalization cannot be a dry run"
''',
    '''[[ "$RELEASE_ACTION" != finalize || "$DRY_RUN" == false ]] \\
  || fail "Release finalization cannot be a dry run"
if [[ "$DRY_RUN" == false && -z "$RELEASE_AUTOMATION_TOKEN" ]]; then
  fail "RELEASE_GITHUB_TOKEN is required for non-dry-run release automation"
fi
''',
)

release_pr_function = r'''open_pull_request_when_configured() {
  local branch=$1 title=$2 body_file=$3 existing pr_url
  [[ -n "$RELEASE_AUTOMATION_TOKEN" ]] \
    || fail "RELEASE_GITHUB_TOKEN is required to create the protected pull request for $branch"

  existing=$(GH_TOKEN="$RELEASE_AUTOMATION_TOKEN" gh pr list \
    --head "$branch" --base main --state open --json number --jq '.[0].number // empty')
  if [[ -z "$existing" ]]; then
    GH_TOKEN="$RELEASE_AUTOMATION_TOKEN" gh pr create \
      --base main \
      --head "$branch" \
      --title "$title" \
      --body-file "$body_file" \
      >/dev/null
  else
    echo "Pull request #$existing already exists for $branch"
  fi

  existing=$(GH_TOKEN="$RELEASE_AUTOMATION_TOKEN" gh pr list \
    --head "$branch" --base main --state open --json number --jq '.[0].number // empty')
  [[ -n "$existing" ]] \
    || fail "Expected a protected pull request for $branch after release preparation"
  pr_url=$(GH_TOKEN="$RELEASE_AUTOMATION_TOKEN" gh pr view "$existing" \
    --json url --jq '.url')
  [[ -n "$pr_url" ]] \
    || fail "Protected pull request #$existing for $branch has no URL"
  echo "Protected pull request #$existing exists for $branch: $pr_url"
  append_summary "Protected pull request [#$existing]($pr_url) exists for \`$branch\`."
}

write_release_candidate() {'''
regex_replace_once(
    ".github/scripts/release.sh",
    r"open_pull_request_when_configured\(\) \{\n.*?\n\}\n\nwrite_release_candidate\(\) \{",
    release_pr_function,
    flags=re.DOTALL,
)

# Extend the consistency verifier with a deliberately small release-status vocabulary. Public
# documentation may contain historical versions, but claims about the current public release,
# an upcoming release or the active snapshot must agree with the generated version files.
verifier_path = ".github/scripts/verify-release-consistency.py"
verifier = read(verifier_path)
constant_anchor = '''REQUIRED_RELEASE_OPTIONS = (
    "--generate-notes",
    "--verify-tag",
    "--fail-on-no-commits",
)
'''
constant_replacement = constant_anchor + '''RELEASE_STATUS_FILES = (
    Path("README.md"),
    Path("docs/consuming.md"),
    Path("jgit-storage-hibernate-bom/README.md"),
)
CURRENT_PUBLIC_CLAIM = re.compile(
    r"\\b(?:current|latest)\\s+public\\b[^\\n0-9]{0,64}`?"
    r"([0-9]+\\.[0-9]+\\.[0-9]+)`?",
    re.IGNORECASE,
)
UPCOMING_RELEASE_CLAIM = re.compile(
    r"\\bupcoming\\b[^\\n0-9]{0,64}`?([0-9]+\\.[0-9]+\\.[0-9]+)`?",
    re.IGNORECASE,
)
SNAPSHOT_REFERENCE = re.compile(
    r"(?<![A-Za-z0-9])([0-9]+\\.[0-9]+\\.[0-9]+-SNAPSHOT)(?![A-Za-z0-9])"
)
'''
if verifier.count(constant_anchor) != 1:
    raise SystemExit("Could not find release verifier constant insertion point")
verifier = verifier.replace(constant_anchor, constant_replacement, 1)

workflow_anchor = "def verify_release_workflow(errors: list[str]) -> None:\n"
status_function = '''def semantic_version(value: str) -> tuple[int, int, int]:
    return tuple(map(int, value.removesuffix("-SNAPSHOT").split(".")))


def verify_release_status_prose(
    project_version: str, documented_version: str, errors: list[str]
) -> None:
    expected_sentence = f"The documented release line is **{documented_version}**."

    for path in RELEASE_STATUS_FILES:
        text = required_text(path, errors)
        if not text:
            continue
        if expected_sentence not in text:
            fail(
                errors,
                f"{path} must contain generated release-status sentence "
                f"{expected_sentence!r}",
            )

        for match in CURRENT_PUBLIC_CLAIM.finditer(text):
            current = match.group(1)
            if current != documented_version:
                fail(
                    errors,
                    f"{path} describes {current} as current/latest public release or BOM; "
                    f"expected {documented_version}",
                )

        for match in UPCOMING_RELEASE_CLAIM.finditer(text):
            upcoming = match.group(1)
            if semantic_version(upcoming) <= semantic_version(documented_version):
                fail(
                    errors,
                    f"{path} describes already released {upcoming} as upcoming; "
                    f"documented release is {documented_version}",
                )

        for snapshot in SNAPSHOT_REFERENCE.findall(text):
            if snapshot != project_version:
                fail(
                    errors,
                    f"{path} contains stale snapshot reference {snapshot}; "
                    f"reactor version is {project_version}",
                )


'''
if verifier.count(workflow_anchor) != 1:
    raise SystemExit("Could not find release verifier function insertion point")
verifier = verifier.replace(workflow_anchor, status_function + workflow_anchor, 1)
call_anchor = '''    verify_documentation_snippets(documented_version, java_version, errors)
    verify_release_workflow(errors)
'''
call_replacement = '''    verify_documentation_snippets(documented_version, java_version, errors)
    verify_release_status_prose(project_version, documented_version, errors)
    verify_release_workflow(errors)
'''
if verifier.count(call_anchor) != 1:
    raise SystemExit("Could not find release verifier call insertion point")
write(verifier_path, verifier.replace(call_anchor, call_replacement, 1))

# Run release-status regression tests as part of the existing repository-owned release test suite.
workflow_test_path = ".github/scripts/test_release_workflow_trigger.py"
workflow_test = read(workflow_test_path)
suite_anchor = '''RECOVERY_REGRESSION_TESTS = (
    Path(__file__).with_name("test_recover_partial_release.py"),
    Path(__file__).with_name("test_publish_snapshot_workflow.py"),
)
'''
suite_replacement = '''RECOVERY_REGRESSION_TESTS = (
    Path(__file__).with_name("test_recover_partial_release.py"),
    Path(__file__).with_name("test_publish_snapshot_workflow.py"),
    Path(__file__).with_name("test_release_status_consistency.py"),
)
'''
if workflow_test.count(suite_anchor) != 1:
    raise SystemExit("Could not find release regression suite tuple")
workflow_test = workflow_test.replace(suite_anchor, suite_replacement, 1)
method_anchor = '''    def test_recovery_regression_suites_are_executed(self) -> None:
'''
method_replacement = '''    def test_non_dry_run_release_requires_automation_token(self) -> None:
        self.assertIn(
            "RELEASE_GITHUB_TOKEN is required for non-dry-run release automation",
            self.script,
        )
        self.assertNotIn("No RELEASE_GITHUB_TOKEN is configured", self.script)

    def test_release_preparation_asserts_pull_request_exists(self) -> None:
        self.assertIn("Expected a protected pull request", self.script)
        self.assertIn('gh pr view "$existing"', self.script)

    def test_recovery_regression_suites_are_executed(self) -> None:
'''
if workflow_test.count(method_anchor) != 1:
    raise SystemExit("Could not find release workflow test insertion point")
write(workflow_test_path, workflow_test.replace(method_anchor, method_replacement, 1))

status_test = r'''#!/usr/bin/env python3
"""Regression tests for generated public-release status prose."""

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-release-consistency.py")
SPEC = importlib.util.spec_from_file_location("verify_release_consistency", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseStatusConsistencyTest(unittest.TestCase):
    def test_matching_generated_status_is_accepted(self) -> None:
        with self.fixture() as root:
            errors: list[str] = []
            previous = Path.cwd()
            os.chdir(root)
            try:
                MODULE.verify_release_status_prose(
                    "0.11.1-SNAPSHOT", "0.11.0", errors
                )
            finally:
                os.chdir(previous)
            self.assertEqual([], errors)

    def test_stale_current_upcoming_and_snapshot_claims_are_rejected(self) -> None:
        with self.fixture() as root:
            (root / "README.md").write_text(
                "The documented release line is **0.11.0**.\n"
                "The current public release is `0.10.0`.\n"
                "The upcoming `0.11.0` line is next.\n"
                "The `0.11.0-SNAPSHOT` development line is active.\n",
                encoding="utf-8",
            )
            errors: list[str] = []
            previous = Path.cwd()
            os.chdir(root)
            try:
                MODULE.verify_release_status_prose(
                    "0.11.1-SNAPSHOT", "0.11.0", errors
                )
            finally:
                os.chdir(previous)
            joined = "\n".join(errors)
            self.assertIn("current/latest public release or BOM", joined)
            self.assertIn("already released 0.11.0 as upcoming", joined)
            self.assertIn("stale snapshot reference 0.11.0-SNAPSHOT", joined)

    @staticmethod
    def fixture():
        class Fixture:
            def __enter__(self):
                self.directory = tempfile.TemporaryDirectory()
                root = Path(self.directory.name)
                for path in MODULE.RELEASE_STATUS_FILES:
                    destination = root / path
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_text(
                        "The documented release line is **0.11.0**.\n"
                        "The current public release is `0.11.0`.\n",
                        encoding="utf-8",
                    )
                return root

            def __exit__(self, exc_type, exc_value, traceback):
                self.directory.cleanup()

        return Fixture()


if __name__ == "__main__":
    unittest.main()
'''
write(".github/scripts/test_release_status_consistency.py", status_test)

# Align public release wording with the already published 0.11.0 release. The exact generated
# sentence is advanced automatically by update-release-metadata.py for future releases.
replace_once(
    "README.md",
    "The `0.11.0-SNAPSHOT` development line also contains optional principal-bound Security and secured JGit Smart HTTP capabilities. They keep users, credentials, ACL persistence and Servlet/JGit HTTP dependencies outside Core. These development modules are not contained in the current public `0.10.0` release.",
    "The documented release line is **0.11.0**. It publishes optional principal-bound Security and secured JGit Smart HTTP capabilities while keeping users, credentials, ACL persistence and Servlet/JGit HTTP dependencies outside Core.",
)
replace_once(
    "README.md",
    "| Enforce multi-user repository and protected-ref access | Explicit principal contexts, database grants/ref rules, final direct-JGit checks, revocable tokens and bounded audit | `0.11.0-SNAPSHOT` development capability |",
    "| Enforce multi-user repository and protected-ref access | Explicit principal contexts, database grants/ref rules, final direct-JGit checks, revocable tokens and bounded audit | Supported since `0.11.0` |",
)
replace_once(
    "README.md",
    "| Expose secured clone, fetch and push over JGit Smart HTTP | Request-bound resolver and upload/receive factories over the same Core publication checks | `0.11.0-SNAPSHOT` development capability |",
    "| Expose secured clone, fetch and push over JGit Smart HTTP | Request-bound resolver and upload/receive factories over the same Core publication checks | Supported since `0.11.0` |",
)
replace_once(
    "README.md",
    "Security and Smart HTTP below describe the upcoming `0.11.0` line; they are not artifacts of the current public `0.10.0` release.",
    "Security and Smart HTTP are published artifacts in the documented `0.11.0` release.",
)
replace_once(
    "README.md",
    "Security and Smart HTTP become independently selectable with the upcoming `0.11.0` release; source/reactor builds keep them aligned through `${project.version}`.",
    "Security and Smart HTTP are independently selectable in the documented `0.11.0` release; source/reactor builds keep them aligned through `${project.version}`.",
)

replace_once(
    "docs/consuming.md",
    "The documented released line is **0.1.17**. It uses Java 21, Hibernate ORM 7.4.5.Final, Hibernate Search 8.4.0.Final and Flyway 13.0.0. Keep those versions aligned through the published artifacts and tested deployment stack instead of overriding only one side of the stack.",
    "The documented release line is **0.11.0**. It uses Java 21, JGit 7.7.1.202607240634-r, Hibernate ORM 7.4.5.Final and Hibernate Search 8.4.0.Final. Keep those versions aligned through the published BOM and tested deployment stack instead of overriding only one side of the stack.",
)
replace_once(
    "docs/consuming.md",
    "Optional database-backed security policy (development preview):",
    "Optional database-backed security policy:",
)
replace_once(
    "docs/consuming.md",
    "Security is introduced in the upcoming `0.11.0` line and is not contained in the documented `0.10.0` release. Until `0.11.0` is released, use the snapshot repository and replace the placeholder below with the current snapshot version.",
    "Security is published in the documented `0.11.0` release. It remains optional: consumers add it only when they need stable principals, groups, repository/ref policy, credentials or audit.",
)
replace_once(
    "docs/consuming.md",
    "  <version>X.Y.Z-SNAPSHOT</version>",
    "  <version>0.11.0</version>",
)
replace_once(
    "docs/consuming.md",
    "Phase 1 supplies the explicit access context, Git-generic permission model, deterministic evaluator and migrations. Principal-bound direct-JGit enforcement is delivered separately so Core-only consumers remain unchanged.",
    "The module supplies explicit access contexts, Git-generic permissions, deterministic evaluation, migrations, principal-bound direct-JGit enforcement, credentials/tokens and durable audit while Core-only consumers remain unchanged.",
)

regex_replace_once(
    "jgit-storage-hibernate-bom/README.md",
    r"The current public `0\.10\.0` BOM manages Core, Search, Java Analysis and Architecture\. Security and\nSmart HTTP are development capabilities for the upcoming `0\.11\.0` line and are deliberately not\nclaimed as published `0\.10\.0` artifacts\.",
    "The documented release line is **0.11.0**. The current public `0.11.0` BOM aligns Core,\nSecurity, Smart HTTP, Search, Java Analysis and Architecture without adding runtime capabilities by\nitself.",
)
regex_replace_once(
    "jgit-storage-hibernate-bom/README.md",
    r"The\npublished `0\.10\.0` production coordinates are:",
    "The\npublished `0.11.0` production coordinates are:",
)
regex_replace_once(
    "jgit-storage-hibernate-bom/README.md",
    r"- `io\.github\.carstenartur:jgit-storage-hibernate-core`\n- `io\.github\.carstenartur:jgit-storage-hibernate-search`\n- `io\.github\.carstenartur:jgit-storage-hibernate-java-analysis`\n- `io\.github\.carstenartur:jgit-storage-hibernate-architecture`\n\nThe development `0\.11\.0` BOM additionally aligns:\n\n- `io\.github\.carstenartur:jgit-storage-hibernate-security`\n- `io\.github\.carstenartur:jgit-storage-hibernate-smart-http`",
    "- `io.github.carstenartur:jgit-storage-hibernate-core`\n- `io.github.carstenartur:jgit-storage-hibernate-security`\n- `io.github.carstenartur:jgit-storage-hibernate-smart-http`\n- `io.github.carstenartur:jgit-storage-hibernate-search`\n- `io.github.carstenartur:jgit-storage-hibernate-java-analysis`\n- `io.github.carstenartur:jgit-storage-hibernate-architecture`",
)
replace_once(
    "jgit-storage-hibernate-bom/README.md",
    "Add `jgit-storage-hibernate-security` in the development line for the optional framework-neutral",
    "Add `jgit-storage-hibernate-security` from the public release for the optional framework-neutral",
)

# Refuse to publish the patch if the concrete stale phrases that caused #265 remain.
for file_name in (
    "README.md",
    "docs/consuming.md",
    "jgit-storage-hibernate-bom/README.md",
):
    text = read(file_name)
    stale = (
        "current public `0.10.0`",
        "upcoming `0.11.0`",
        "`0.11.0-SNAPSHOT`",
        "documented released line is **0.1.17**",
        "<version>X.Y.Z-SNAPSHOT</version>",
    )
    remaining = [value for value in stale if value in text]
    if remaining:
        raise SystemExit(f"Stale release wording remains in {file_name}: {remaining}")

print("Applied issue #265 release automation and documentation hardening")
