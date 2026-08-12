#!/usr/bin/env python3
"""Regression tests for anonymous public Maven repository consumption."""

from __future__ import annotations

import os
from pathlib import Path
import stat
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github" / "scripts" / "verify-public-repository-consumption.sh"


class PublicRepositoryConsumptionTest(unittest.TestCase):

    def test_repository_is_active_for_parent_and_transitive_resolution(self) -> None:
        repository_url = "https://example.invalid/releases?channel=stable&source=test"
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            fake_maven = temporary / "fake-maven.py"
            fake_maven.write_text(
                textwrap.dedent(
                    """\
                    #!/usr/bin/env python3
                    from __future__ import annotations

                    import os
                    from pathlib import Path
                    import sys
                    import xml.etree.ElementTree as ET

                    args = sys.argv[1:]
                    settings_path = Path(args[args.index("-s") + 1])
                    local_argument = next(
                        argument for argument in args
                        if argument.startswith("-Dmaven.repo.local=")
                    )
                    local_repository = Path(local_argument.split("=", 1)[1])
                    expected_url = os.environ["EXPECTED_REPOSITORY_URL"]

                    for credential in (
                        "GITHUB_TOKEN",
                        "GH_TOKEN",
                        "GITHUB_ACTOR",
                        "CENTRAL_USERNAME",
                        "CENTRAL_PASSWORD",
                        "MAVEN_CENTRAL_USERNAME",
                        "MAVEN_CENTRAL_PASSWORD",
                        "MAVEN_GPG_KEY",
                        "MAVEN_GPG_PASSPHRASE",
                        "MAVEN_ARGS",
                    ):
                        if credential in os.environ:
                            raise AssertionError(f"credential leaked to Maven: {credential}")

                    namespace = {"s": "http://maven.apache.org/SETTINGS/1.2.0"}
                    settings = ET.parse(settings_path).getroot()
                    active_profiles = [
                        element.text
                        for element in settings.findall(
                            "s:activeProfiles/s:activeProfile", namespace
                        )
                    ]
                    if active_profiles != ["public-release-resolution"]:
                        raise AssertionError(
                            f"unexpected active profiles: {active_profiles}"
                        )

                    repositories = settings.findall(
                        "s:profiles/s:profile/s:repositories/s:repository", namespace
                    )
                    matching = [
                        repository
                        for repository in repositories
                        if repository.findtext("s:id", namespaces=namespace)
                        == "jgit-storage-hibernate-public"
                    ]
                    if len(matching) != 1:
                        raise AssertionError(
                            f"expected one active public repository, got {len(matching)}"
                        )
                    repository = matching[0]
                    if repository.findtext("s:url", namespaces=namespace) != expected_url:
                        raise AssertionError("repository URL was not XML-escaped round-trip safely")
                    if (
                        repository.findtext(
                            "s:releases/s:checksumPolicy", namespaces=namespace
                        )
                        != "fail"
                    ):
                        raise AssertionError("release checksum policy must fail closed")
                    if (
                        repository.findtext(
                            "s:snapshots/s:enabled", namespaces=namespace
                        )
                        != "false"
                    ):
                        raise AssertionError("snapshot resolution must remain disabled")

                    local_repository.mkdir(parents=True, exist_ok=True)
                    invocation_file = local_repository / ".fake-maven-invocations"
                    invocation = (
                        int(invocation_file.read_text(encoding="utf-8"))
                        if invocation_file.exists()
                        else 0
                    )

                    base = local_repository / "io" / "github" / "carstenartur"
                    version = "0.10.0"
                    parent = (
                        base
                        / "jgit-storage-hibernate-parent"
                        / version
                        / f"jgit-storage-hibernate-parent-{version}.pom"
                    )
                    parent.parent.mkdir(parents=True, exist_ok=True)
                    parent.write_text("<project/>", encoding="utf-8")

                    if invocation == 0:
                        if not any(
                            "maven-dependency-plugin:3.11.0:get" in argument
                            for argument in args
                        ):
                            raise AssertionError("first invocation must resolve the parent POM")
                        expected_remote = (
                            "-DremoteRepositories="
                            "jgit-storage-hibernate-public::default::"
                            + expected_url
                        )
                        if expected_remote not in args:
                            raise AssertionError(
                                "command-line and settings repository IDs must be identical"
                            )
                    elif invocation == 1:
                        if not invocation_file.exists():
                            raise AssertionError(
                                "isolated local repository was cleared between Maven phases"
                            )
                        if "dependency:go-offline" not in args:
                            raise AssertionError(
                                "second invocation must resolve the complete consumer graph"
                            )
                        for artifact in (
                            "jgit-storage-hibernate-core",
                            "jgit-storage-hibernate-security",
                            "jgit-storage-hibernate-search",
                            "jgit-storage-hibernate-java-analysis",
                            "jgit-storage-hibernate-architecture",
                        ):
                            jar = base / artifact / version / f"{artifact}-{version}.jar"
                            jar.parent.mkdir(parents=True, exist_ok=True)
                            jar.write_bytes(b"verified")
                    else:
                        raise AssertionError(f"unexpected Maven invocation {invocation + 1}")

                    invocation_file.write_text(str(invocation + 1), encoding="utf-8")
                    print(f"fake Maven invocation {invocation + 1} completed")
                    """
                ),
                encoding="utf-8",
            )
            fake_maven.chmod(
                fake_maven.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH
            )

            environment = os.environ.copy()
            environment.update(
                {
                    "MAVEN_COMMAND": str(fake_maven),
                    "EXPECTED_REPOSITORY_URL": repository_url,
                    "PUBLIC_REPOSITORY_ATTEMPTS": "1",
                    "GITHUB_TOKEN": "must-not-leak",
                    "GH_TOKEN": "must-not-leak",
                    "CENTRAL_USERNAME": "must-not-leak",
                    "CENTRAL_PASSWORD": "must-not-leak",
                    "MAVEN_ARGS": "must-not-leak",
                }
            )
            result = subprocess.run(
                ["bash", str(SCRIPT), "0.10.0", repository_url],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertEqual(
            0,
            result.returncode,
            msg=f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )
        self.assertIn("fake Maven invocation 1 completed", result.stdout)
        self.assertIn("fake Maven invocation 2 completed", result.stdout)
        self.assertIn(
            "Anonymous public repository consumption and checksum validation verified",
            result.stdout,
        )


if __name__ == "__main__":
    unittest.main()
