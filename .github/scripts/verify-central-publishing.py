#!/usr/bin/env python3
"""Verify the static Maven Central and secondary GitHub Packages publishing contract."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ROOT_POM = Path("pom.xml")
MAVEN_WORKFLOW = Path(".github/workflows/maven.yml")
RELEASE_WORKFLOW = Path(".github/workflows/release.yml")
SNAPSHOT_WORKFLOW = Path(".github/workflows/publish-snapshot.yml")
RELEASE_SCRIPT = Path(".github/scripts/release.sh")
CONSUMER_POM = Path(".github/central-consumer/pom.xml")
BUNDLE_VERIFIER = Path(".github/scripts/verify-central-bundle.py")
CONSUMER_VERIFIER = Path(".github/scripts/verify-central-consumption.sh")
PUBLIC_ARTIFACTS = {
    "jgit-storage-hibernate-parent",
    "jgit-storage-hibernate-core",
    "jgit-storage-hibernate-search",
    "jgit-storage-hibernate-java-analysis",
    "jgit-storage-hibernate-architecture",
    "jgit-storage-hibernate-benchmarks",
}
JAR_MODULE_POMS = tuple(sorted(Path(".").glob("jgit-storage-hibernate-*/pom.xml")))


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def read(path: Path, errors: list[str]) -> str:
    if not path.is_file():
        fail(errors, f"missing required file: {path}")
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exception:
        fail(errors, f"cannot read {path}: {exception}")
        return ""


def parse(path: Path, errors: list[str]) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exception:
        fail(errors, f"cannot parse {path}: {exception}")
        return None


def text(element: ET.Element | None, path: str) -> str:
    if element is None:
        return ""
    value = element.findtext(path, default="", namespaces=NS)
    return value.strip()


def profile(root: ET.Element, profile_id: str) -> ET.Element | None:
    for candidate in root.findall("m:profiles/m:profile", NS):
        if text(candidate, "m:id") == profile_id:
            return candidate
    return None


def plugin(parent: ET.Element | None, group_id: str, artifact_id: str) -> ET.Element | None:
    if parent is None:
        return None
    for candidate in parent.findall(".//m:plugin", NS):
        candidate_group = text(candidate, "m:groupId") or "org.apache.maven.plugins"
        if candidate_group == group_id and text(candidate, "m:artifactId") == artifact_id:
            return candidate
    return None


def execution_has(plugin_element: ET.Element | None, phase: str, goal: str) -> bool:
    if plugin_element is None:
        return False
    for execution in plugin_element.findall("m:executions/m:execution", NS):
        if text(execution, "m:phase") != phase:
            continue
        goals = {
            goal_element.text.strip()
            for goal_element in execution.findall("m:goals/m:goal", NS)
            if goal_element.text
        }
        if goal in goals:
            return True
    return False


def verify_root_pom(errors: list[str]) -> None:
    root = parse(ROOT_POM, errors)
    if root is None:
        return

    if root.find("m:distributionManagement", NS) is not None:
        fail(errors, "pom.xml must not publish to a repository without an explicit profile")

    required_metadata = {
        "name": text(root, "m:name"),
        "description": text(root, "m:description"),
        "url": text(root, "m:url"),
        "license": text(root, "m:licenses/m:license/m:name"),
        "developer": text(root, "m:developers/m:developer/m:name"),
        "scm": text(root, "m:scm/m:url"),
    }
    for name, value in required_metadata.items():
        if not value:
            fail(errors, f"pom.xml is missing Central-required {name} metadata")

    properties = root.find("m:properties", NS)
    if properties is None:
        fail(errors, "pom.xml has no properties")
        return
    if text(properties, "m:central-publishing-maven-plugin.version") != "0.11.0":
        fail(errors, "Central publishing plugin must be pinned to 0.11.0")
    if text(properties, "m:maven-gpg-plugin.version") != "3.2.8":
        fail(errors, "Maven GPG plugin must be pinned to 3.2.8")

    github = profile(root, "github-packages")
    if github is None:
        fail(errors, "pom.xml is missing the github-packages profile")
    else:
        repository = github.find("m:distributionManagement/m:repository", NS)
        snapshot = github.find("m:distributionManagement/m:snapshotRepository", NS)
        for label, element in (("release", repository), ("snapshot", snapshot)):
            if text(element, "m:id") != "github":
                fail(errors, f"github-packages {label} repository must use server id github")
            if "maven.pkg.github.com/carstenartur/jgit-storage-hibernate" not in text(
                element, "m:url"
            ):
                fail(errors, f"github-packages {label} repository has the wrong URL")

    central = profile(root, "central-release")
    if central is None:
        fail(errors, "pom.xml is missing the central-release profile")
        return

    central_plugin = plugin(
        central,
        "org.sonatype.central",
        "central-publishing-maven-plugin",
    )
    if central_plugin is None:
        fail(errors, "central-release profile has no Central publishing plugin")
    else:
        if text(central_plugin, "m:version") != "${central-publishing-maven-plugin.version}":
            fail(errors, "Central publishing plugin must use the pinned version property")
        if text(central_plugin, "m:extensions") != "true":
            fail(errors, "Central publishing plugin must be a Maven extension")
        configuration = central_plugin.find("m:configuration", NS)
        expected = {
            "publishingServerId": "central",
            "autoPublish": "true",
            "waitUntil": "published",
            "skipPublishing": "${central.skipPublishing}",
        }
        for key, value in expected.items():
            if text(configuration, f"m:{key}") != value:
                fail(errors, f"Central publishing configuration {key} must be {value!r}")

    gpg = plugin(central, "org.apache.maven.plugins", "maven-gpg-plugin")
    if gpg is None:
        fail(errors, "central-release profile has no Maven GPG plugin")
    else:
        if not execution_has(gpg, "verify", "sign"):
            fail(errors, "Maven GPG plugin must sign artifacts during verify")
        configuration = gpg.find("m:configuration", NS)
        expected = {
            "signer": "bc",
            "bestPractices": "true",
            "keyEnvName": "MAVEN_GPG_KEY",
            "passphraseEnvName": "MAVEN_GPG_PASSPHRASE",
        }
        for key, value in expected.items():
            if text(configuration, f"m:{key}") != value:
                fail(errors, f"Maven GPG configuration {key} must be {value!r}")

    plugin_management = root.find("m:build/m:pluginManagement", NS)
    source = plugin(plugin_management, "org.apache.maven.plugins", "maven-source-plugin")
    javadoc = plugin(plugin_management, "org.apache.maven.plugins", "maven-javadoc-plugin")
    if not execution_has(source, "package", "jar-no-fork"):
        fail(errors, "source JARs must be attached during package")
    if not execution_has(javadoc, "package", "jar"):
        fail(errors, "Javadoc JARs must be attached during package")


def verify_module_attachments(errors: list[str]) -> None:
    for path in JAR_MODULE_POMS:
        root = parse(path, errors)
        if root is None:
            continue
        plugins = root.find("m:build/m:plugins", NS)
        for artifact_id in ("maven-source-plugin", "maven-javadoc-plugin"):
            if plugin(plugins, "org.apache.maven.plugins", artifact_id) is None:
                fail(errors, f"{path} does not attach {artifact_id} for Central")


def verify_workflows_and_scripts(errors: list[str]) -> None:
    release_workflow = read(RELEASE_WORKFLOW, errors)
    for fragment in (
        "server-id: central",
        "MAVEN_CENTRAL_USERNAME",
        "MAVEN_CENTRAL_PASSWORD",
        "MAVEN_CENTRAL_GPG_PRIVATE_KEY",
        "MAVEN_CENTRAL_GPG_PASSPHRASE",
        "publish_github_packages",
        ".github/scripts/release.sh",
    ):
        if fragment not in release_workflow:
            fail(errors, f"{RELEASE_WORKFLOW} is missing {fragment!r}")

    maven_workflow = read(MAVEN_WORKFLOW, errors)
    for fragment in (
        "Maven Central release contract",
        "set -o pipefail",
        "DRY_RUN=true",
        "PUBLISH_GITHUB_PACKAGES=false",
        "if-no-files-found: error",
        "target/central-publishing/*.zip",
    ):
        if fragment not in maven_workflow:
            fail(errors, f"{MAVEN_WORKFLOW} is missing {fragment!r}")

    snapshot_workflow = read(SNAPSHOT_WORKFLOW, errors)
    if "-Pgithub-packages" not in snapshot_workflow:
        fail(errors, f"{SNAPSHOT_WORKFLOW} must activate github-packages explicitly")

    release_script = read(RELEASE_SCRIPT, errors)
    for fragment in (
        "-Pcentral-release",
        "-Pgithub-packages",
        "verify-central-bundle.py",
        "verify-central-consumption.sh",
        "verify-central-publishing.py",
        "CENTRAL_USERNAME",
        "CENTRAL_PASSWORD",
        "MAVEN_GPG_KEY",
        "MAVEN_GPG_PASSPHRASE",
        "PUBLISH_GITHUB_PACKAGES",
        "CENTRAL_DRY_RUN_SETTINGS",
        "bundle-only-dry-run",
        "-Dcentral.skipPublishing=true",
    ):
        if fragment not in release_script:
            fail(errors, f"{RELEASE_SCRIPT} is missing {fragment!r}")

    for path in (BUNDLE_VERIFIER, CONSUMER_VERIFIER):
        read(path, errors)


def verify_consumer_project(errors: list[str]) -> None:
    root = parse(CONSUMER_POM, errors)
    if root is None:
        return
    if root.find("m:repositories", NS) is not None:
        fail(errors, f"{CONSUMER_POM} must not declare any repository")

    artifacts = {
        text(dependency, "m:artifactId")
        for dependency in root.findall("m:dependencies/m:dependency", NS)
        if text(dependency, "m:groupId") == "io.github.carstenartur"
    }
    expected_jars = PUBLIC_ARTIFACTS - {"jgit-storage-hibernate-parent"}
    if artifacts != expected_jars:
        fail(
            errors,
            f"{CONSUMER_POM} artifacts {sorted(artifacts)} do not equal {sorted(expected_jars)}",
        )


def main() -> None:
    errors: list[str] = []
    verify_root_pom(errors)
    verify_module_attachments(errors)
    verify_workflows_and_scripts(errors)
    verify_consumer_project(errors)

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

    print("Maven Central publishing contract verified")


if __name__ == "__main__":
    main()
