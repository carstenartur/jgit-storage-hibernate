#!/usr/bin/env python3
"""Verify the published BOM and its anonymous-consumer contract."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

MAVEN = "{http://maven.apache.org/POM/4.0.0}"
GROUP_ID = "io.github.carstenartur"
BOM = "jgit-storage-hibernate-bom"
PRODUCTION_MODULES = {
    "jgit-storage-hibernate-core",
    "jgit-storage-hibernate-security",
    "jgit-storage-hibernate-smart-http",
    "jgit-storage-hibernate-search",
    "jgit-storage-hibernate-java-analysis",
    "jgit-storage-hibernate-architecture",
}
BENCHMARKS = "jgit-storage-hibernate-benchmarks"


def value(element: ET.Element | None, child: str, default: str = "") -> str:
    if element is None:
        return default
    found = element.findtext(MAVEN + child)
    return found.strip() if found else default


def dependencies(parent: ET.Element | None) -> list[ET.Element]:
    if parent is None:
        return []
    container = parent.find(MAVEN + "dependencies")
    return [] if container is None else list(container.findall(MAVEN + "dependency"))


def verify(root: Path) -> dict[str, object]:
    violations: list[str] = []

    root_pom = ET.parse(root / "pom.xml").getroot()
    modules_node = root_pom.find(MAVEN + "modules")
    reactor_modules = {
        module.text.strip()
        for module in ([] if modules_node is None else modules_node.findall(MAVEN + "module"))
        if module.text
    }
    if BOM not in reactor_modules:
        violations.append(f"Root reactor does not include {BOM}")

    bom_pom = ET.parse(root / BOM / "pom.xml").getroot()
    if value(bom_pom, "artifactId") != BOM:
        violations.append("BOM artifactId is incorrect")
    if value(bom_pom, "packaging", "jar") != "pom":
        violations.append("Consumer BOM must use pom packaging")
    if bom_pom.find(MAVEN + "dependencies") is not None:
        violations.append("Consumer BOM must not declare direct dependencies")

    managed = dependencies(bom_pom.find(MAVEN + "dependencyManagement"))
    managed_artifacts: set[str] = set()
    for dependency in managed:
        group_id = value(dependency, "groupId")
        artifact_id = value(dependency, "artifactId")
        version = value(dependency, "version")
        if group_id not in {GROUP_ID, "${project.groupId}"}:
            violations.append(
                f"BOM manages foreign dependency {group_id}:{artifact_id}"
            )
        if artifact_id == BENCHMARKS:
            violations.append("BOM must not manage the benchmark artifact")
        managed_artifacts.add(artifact_id)
        if version != "${project.version}":
            violations.append(
                f"{artifact_id} is not aligned through ${{project.version}}"
            )
    if managed_artifacts != PRODUCTION_MODULES:
        violations.append(
            "Managed production modules differ: expected "
            + ", ".join(sorted(PRODUCTION_MODULES))
            + "; found "
            + ", ".join(sorted(managed_artifacts))
        )

    consumer_path = root / ".github/public-repository-consumer/pom.xml"
    consumer = ET.parse(consumer_path).getroot()
    imports = dependencies(consumer.find(MAVEN + "dependencyManagement"))
    bom_imports = [
        dependency
        for dependency in imports
        if value(dependency, "groupId") == GROUP_ID
        and value(dependency, "artifactId") == BOM
    ]
    if len(bom_imports) != 1:
        violations.append("Anonymous consumer must import the BOM exactly once")
    else:
        bom_import = bom_imports[0]
        if value(bom_import, "type", "jar") != "pom":
            violations.append("Anonymous consumer BOM import must use type pom")
        if value(bom_import, "scope", "compile") != "import":
            violations.append("Anonymous consumer BOM import must use import scope")
        if not value(bom_import, "version"):
            violations.append("Anonymous consumer BOM import needs an explicit release version")

    selected_modules: set[str] = set()
    for dependency in dependencies(consumer):
        if value(dependency, "groupId") != GROUP_ID:
            continue
        artifact_id = value(dependency, "artifactId")
        if artifact_id.startswith("jgit-storage-hibernate-"):
            selected_modules.add(artifact_id)
            if artifact_id in PRODUCTION_MODULES and value(dependency, "version"):
                violations.append(
                    f"Anonymous consumer overrides the BOM version for {artifact_id}"
                )
    if not selected_modules & PRODUCTION_MODULES:
        violations.append("Anonymous consumer selects no production module")
    if BENCHMARKS in selected_modules:
        violations.append("Anonymous consumer must not select the benchmark artifact")

    return {
        "reactorModules": sorted(reactor_modules),
        "managedModules": sorted(managed_artifacts),
        "consumerSelectedModules": sorted(selected_modules),
        "violations": violations,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--report", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = verify(args.root)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    if report["violations"]:
        raise SystemExit("BOM contract violations:\n- " + "\n- ".join(report["violations"]))
    print(
        "Verified alignment-only consumer BOM: "
        + ", ".join(report["managedModules"])
    )


if __name__ == "__main__":
    main()
