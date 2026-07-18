#!/usr/bin/env python3
"""Verify Maven, metadata, documentation, and the static release contract."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?$")
RELEASE_SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
CONCRETE_SEMVER = re.compile(
    r"(?<![A-Za-z0-9])([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)(?![A-Za-z0-9])"
)
PROJECT_GROUP_ID = "io.github.carstenartur"
PROJECT_ARTIFACT_PREFIX = "jgit-storage-hibernate-"
DOCUMENTATION_VERSION_FILE = Path("docs/current-release-version.txt")
RELEASE_WORKFLOW_FILE = Path(".github/workflows/release.yml")
RELEASE_NOTES_FILE = Path(".github/release.yml")
RELEASE_SCRIPT_FILE = Path(".github/scripts/release.sh")
METADATA_JSON_FILES = (Path(".zenodo.json"), Path("codemeta.json"))
ALIGNED_TEST_COORDINATES = {
    ("org.flywaydb", "flyway-core"),
    ("org.flywaydb", "flyway-database-postgresql"),
    ("org.postgresql", "postgresql"),
    ("org.testcontainers", "testcontainers-junit-jupiter"),
    ("org.testcontainers", "testcontainers-postgresql"),
}
PUBLIC_MARKDOWN_GLOBS = (
    "README.md",
    "docs/**/*.md",
    "jgit-storage-hibernate-*/README.md",
)
DOCUMENTATION_VERSION_PLACEHOLDERS = {
    "...",
    "X.Y.Z",
    "X.Y.Z-SNAPSHOT",
    "${project.version}",
    "${revision}",
}
REQUIRED_RELEASE_OPTIONS = (
    "--generate-notes",
    "--verify-tag",
    "--fail-on-no-commits",
)


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def required_text(path: Path, errors: list[str]) -> str:
    if not path.is_file():
        fail(errors, f"missing required file: {path}")
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exception:
        fail(errors, f"cannot read {path}: {exception}")
        return ""


def pom_root(path: Path, errors: list[str]) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exception:
        fail(errors, f"cannot parse {path}: {exception}")
        return None


def direct_text(root: ET.Element, child: str) -> str | None:
    element = root.find(f"m:{child}", MAVEN_NS)
    return element.text.strip() if element is not None and element.text else None


def root_project_version(errors: list[str]) -> tuple[str, str]:
    root = pom_root(Path("pom.xml"), errors)
    if root is None:
        return "", ""

    version = direct_text(root, "version") or ""
    properties = root.find("m:properties", MAVEN_NS)
    java_version = ""
    if properties is not None:
        element = properties.find("m:java.version", MAVEN_NS)
        if element is not None and element.text:
            java_version = element.text.strip()

    if not SEMVER.fullmatch(version):
        fail(errors, f"root pom.xml has invalid project version: {version!r}")
    if not java_version.isdigit():
        fail(errors, f"root pom.xml has invalid java.version: {java_version!r}")
    return version, java_version


def module_pom_paths() -> list[Path]:
    return sorted(Path(".").glob("jgit-storage-hibernate-*/pom.xml"))


def verify_module_poms(project_version: str, errors: list[str]) -> None:
    for path in module_pom_paths():
        root = pom_root(path, errors)
        if root is None:
            continue

        parent = root.find("m:parent", MAVEN_NS)
        if parent is None:
            fail(errors, f"{path} has no parent")
            continue

        parent_group = parent.findtext("m:groupId", default="", namespaces=MAVEN_NS).strip()
        parent_artifact = parent.findtext(
            "m:artifactId", default="", namespaces=MAVEN_NS
        ).strip()
        parent_version = parent.findtext(
            "m:version", default="", namespaces=MAVEN_NS
        ).strip()

        if parent_group != PROJECT_GROUP_ID or parent_artifact != "jgit-storage-hibernate-parent":
            fail(errors, f"{path} does not use the project parent")
        if parent_version != project_version:
            fail(
                errors,
                f"{path} parent version {parent_version!r} does not match {project_version!r}",
            )


def verify_project_dependencies(project_version: str, errors: list[str]) -> None:
    allowed_expressions = {
        "${project.version}",
        "${project.parent.version}",
        "${pom.version}",
        "${revision}",
    }
    for path in [Path("pom.xml"), *module_pom_paths()]:
        root = pom_root(path, errors)
        if root is None:
            continue

        for dependency in root.findall(".//m:dependency", MAVEN_NS):
            group_id = dependency.findtext(
                "m:groupId", default="", namespaces=MAVEN_NS
            ).strip()
            artifact_id = dependency.findtext(
                "m:artifactId", default="", namespaces=MAVEN_NS
            ).strip()
            version = dependency.findtext(
                "m:version", default="", namespaces=MAVEN_NS
            ).strip()

            if group_id != PROJECT_GROUP_ID or not artifact_id.startswith(
                PROJECT_ARTIFACT_PREFIX
            ):
                continue
            if version and version not in allowed_expressions and version != project_version:
                fail(
                    errors,
                    f"{path} uses project dependency {artifact_id}:{version}; "
                    f"expected dependencyManagement, a project version expression, or {project_version}",
                )


def verify_aligned_test_dependencies(errors: list[str]) -> None:
    versions: dict[tuple[str, str], dict[str, list[Path]]] = {}
    for path in module_pom_paths():
        root = pom_root(path, errors)
        if root is None:
            continue

        for dependency in root.findall(".//m:dependency", MAVEN_NS):
            group_id = dependency.findtext(
                "m:groupId", default="", namespaces=MAVEN_NS
            ).strip()
            artifact_id = dependency.findtext(
                "m:artifactId", default="", namespaces=MAVEN_NS
            ).strip()
            coordinate = (group_id, artifact_id)
            if coordinate not in ALIGNED_TEST_COORDINATES:
                continue

            version = dependency.findtext(
                "m:version", default="", namespaces=MAVEN_NS
            ).strip()
            if version:
                versions.setdefault(coordinate, {}).setdefault(version, []).append(path)

    for coordinate, by_version in sorted(versions.items()):
        if len(by_version) <= 1:
            continue
        detail = ", ".join(
            f"{version} in {', '.join(str(path) for path in paths)}"
            for version, paths in sorted(by_version.items())
        )
        fail(errors, f"inconsistent {coordinate[0]}:{coordinate[1]} versions: {detail}")


def verify_metadata(project_version: str, java_version: str, errors: list[str]) -> None:
    cff = required_text(Path("CITATION.cff"), errors)
    match = re.search(r'^version:\s*["\']?([^"\'\s]+)["\']?\s*$', cff, flags=re.MULTILINE)
    if not match or match.group(1) != project_version:
        fail(errors, f"CITATION.cff version does not match {project_version}")

    citation_md = required_text(Path("CITATION.md"), errors)
    cited_versions = set(
        re.findall(
            r"(?:Version\s+|version\s*=\s*\{)"
            r"([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)",
            citation_md,
        )
    )
    if cited_versions != {project_version}:
        fail(
            errors,
            f"CITATION.md versions {sorted(cited_versions)} do not equal [{project_version!r}]",
        )

    for path in METADATA_JSON_FILES:
        text = required_text(path, errors)
        if not text:
            continue
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exception:
            fail(errors, f"cannot parse {path}: {exception}")
            continue

        if data.get("version") != project_version:
            fail(
                errors,
                f"{path} version {data.get('version')!r} does not match {project_version!r}",
            )

        if path.name == "codemeta.json":
            expected_runtime = f"Java {java_version} or later"
            if data.get("runtimePlatform") != expected_runtime:
                fail(
                    errors,
                    f"codemeta.json runtimePlatform {data.get('runtimePlatform')!r} "
                    f"does not match {expected_runtime!r}",
                )


def documentation_version(errors: list[str]) -> str:
    version = required_text(DOCUMENTATION_VERSION_FILE, errors).strip()
    if not RELEASE_SEMVER.fullmatch(version):
        fail(errors, f"{DOCUMENTATION_VERSION_FILE} must contain one X.Y.Z release version")
    return version


def markdown_files() -> list[Path]:
    paths: set[Path] = set()
    for pattern in PUBLIC_MARKDOWN_GLOBS:
        paths.update(path for path in Path(".").glob(pattern) if path.is_file())
    return sorted(path for path in paths if "docs/releases" not in path.as_posix())


def project_dependency_version(block: str) -> tuple[str, str] | None:
    group_match = re.search(r"<groupId>\s*([^<]+)\s*</groupId>", block)
    artifact_match = re.search(r"<artifactId>\s*([^<]+)\s*</artifactId>", block)
    if group_match is None or artifact_match is None:
        return None
    group_id = group_match.group(1).strip()
    artifact_id = artifact_match.group(1).strip()
    if group_id != PROJECT_GROUP_ID or not artifact_id.startswith(PROJECT_ARTIFACT_PREFIX):
        return None
    version_match = re.search(r"<version>\s*([^<]+)\s*</version>", block)
    return artifact_id, version_match.group(1).strip() if version_match else ""


def verify_documentation_snippets(
    documented_version: str, java_version: str, errors: list[str]
) -> None:
    dependency_block_pattern = re.compile(r"<dependency>.*?</dependency>", re.DOTALL)
    coordinate_pattern = re.compile(
        re.escape(PROJECT_GROUP_ID)
        + r":"
        + re.escape(PROJECT_ARTIFACT_PREFIX)
        + r"[A-Za-z0-9_.-]+:([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)"
    )

    found_dependency = False
    for path in markdown_files():
        text = required_text(path, errors)
        if not text:
            continue

        for block in dependency_block_pattern.findall(text):
            project_dependency = project_dependency_version(block)
            if project_dependency is None:
                continue
            found_dependency = True
            artifact_id, version = project_dependency
            if not version:
                fail(errors, f"{path} project dependency {artifact_id} has no version")
                continue
            if version in DOCUMENTATION_VERSION_PLACEHOLDERS:
                continue
            if not SEMVER.fullmatch(version):
                fail(
                    errors,
                    f"{path} documents {artifact_id} with unsupported version value {version!r}",
                )
            elif version != documented_version:
                fail(
                    errors,
                    f"{path} documents {artifact_id}:{version}; expected {documented_version}",
                )

        for version in coordinate_pattern.findall(text):
            found_dependency = True
            if version != documented_version:
                fail(
                    errors,
                    f"{path} contains a project coordinate with version {version}; "
                    f"expected {documented_version}",
                )

    if not found_dependency:
        fail(errors, "no public Maven dependency snippet for a project artifact was found")

    readme = required_text(Path("README.md"), errors)
    if f"Java-{java_version}" not in readme and f"Java {java_version}" not in readme:
        fail(errors, f"README.md does not advertise the Java {java_version} baseline")


def verify_release_workflow(errors: list[str]) -> None:
    text = required_text(RELEASE_WORKFLOW_FILE, errors)
    if not text:
        return

    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = line.lstrip()
        if not (stripped.startswith("description:") or stripped.startswith("default:")):
            continue
        for version in CONCRETE_SEMVER.findall(line):
            fail(
                errors,
                f"{RELEASE_WORKFLOW_FILE}:{line_number} contains concrete version "
                f"example {version!r}; use X.Y.Z or X.Y.Z-SNAPSHOT",
            )

    if "X.Y.Z" not in text:
        fail(errors, f"{RELEASE_WORKFLOW_FILE} does not document the X.Y.Z placeholder")
    if "X.Y.Z-SNAPSHOT" not in text:
        fail(
            errors,
            f"{RELEASE_WORKFLOW_FILE} does not document the X.Y.Z-SNAPSHOT placeholder",
        )
    if ".github/scripts/release.sh" not in text:
        fail(errors, f"{RELEASE_WORKFLOW_FILE} does not delegate to release.sh")
    if "gh release create" in text:
        fail(
            errors,
            f"{RELEASE_WORKFLOW_FILE} duplicates release creation instead of delegating to release.sh",
        )
    if "docker info" in text:
        fail(
            errors,
            f"{RELEASE_WORKFLOW_FILE} duplicates Docker release policy instead of delegating to release.sh",
        )


def release_note_category_summary(text: str) -> tuple[int, bool]:
    lines = text.splitlines()
    categories_index: int | None = None
    categories_indent = 0

    for index, line in enumerate(lines):
        if line.strip() == "categories:":
            categories_index = index
            categories_indent = len(line) - len(line.lstrip())
            break

    if categories_index is None:
        return 0, False

    category_count = 0
    catch_all = False
    in_category = False
    in_labels = False

    for line in lines[categories_index + 1 :]:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        indent = len(line) - len(line.lstrip())
        if indent <= categories_indent:
            break

        if stripped.startswith("- title:"):
            category_count += 1
            in_category = True
            in_labels = False
            continue

        if in_category and stripped == "labels:":
            in_labels = True
            continue

        if in_category and in_labels and stripped.startswith("- "):
            label = stripped[2:].strip().strip("\"'")
            if label == "*":
                catch_all = True

    return category_count, catch_all


def verify_release_notes_configuration(errors: list[str]) -> None:
    text = required_text(RELEASE_NOTES_FILE, errors)
    if not text:
        return

    if not re.search(r"(?m)^\s*changelog:\s*$", text):
        fail(errors, f"{RELEASE_NOTES_FILE} has no changelog configuration")

    category_count, catch_all = release_note_category_summary(text)
    if category_count < 2:
        fail(
            errors,
            f"{RELEASE_NOTES_FILE} must define categorized release notes; "
            f"found {category_count} categories",
        )
    if not catch_all:
        fail(
            errors,
            f'{RELEASE_NOTES_FILE} must include a catch-all category with label "*"',
        )


def continued_shell_command(text: str, command_prefix: str) -> str | None:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("#") or not stripped.startswith(command_prefix):
            continue

        parts = [stripped]
        while parts[-1].rstrip().endswith("\\") and index + 1 < len(lines):
            parts[-1] = parts[-1].rstrip()[:-1].rstrip()
            index += 1
            parts.append(lines[index].strip())
        return " ".join(parts)
    return None


def verify_release_script(errors: list[str]) -> None:
    text = required_text(RELEASE_SCRIPT_FILE, errors)
    if not text:
        return

    command = continued_shell_command(text, "gh release create")
    if command is None:
        fail(errors, f"{RELEASE_SCRIPT_FILE} does not create a GitHub release")
        return

    command_parts = command.split()
    for option in REQUIRED_RELEASE_OPTIONS:
        if option not in command_parts:
            fail(
                errors,
                f"{RELEASE_SCRIPT_FILE} gh release create command is missing {option}",
            )

    required_fragments = (
        "DOCUMENTED_RELEASE_VERSION_FILE",
        "docker info",
        "SKIP_TESTS",
        "DRY_RUN",
    )
    for fragment in required_fragments:
        if fragment not in text:
            fail(errors, f"{RELEASE_SCRIPT_FILE} is missing release safeguard {fragment!r}")


def main() -> None:
    errors: list[str] = []
    project_version, java_version = root_project_version(errors)
    documented_version = documentation_version(errors)

    verify_module_poms(project_version, errors)
    verify_project_dependencies(project_version, errors)
    verify_aligned_test_dependencies(errors)
    verify_metadata(project_version, java_version, errors)
    verify_documentation_snippets(documented_version, java_version, errors)
    verify_release_workflow(errors)
    verify_release_notes_configuration(errors)
    verify_release_script(errors)

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        "Repository consistency verified: "
        f"project={project_version}, docs={documented_version}, java={java_version}"
    )


if __name__ == "__main__":
    main()
