#!/usr/bin/env python3
"""Verify that published modules remain composable consumer capabilities."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

MAVEN = "{http://maven.apache.org/POM/4.0.0}"
GROUP_ID = "io.github.carstenartur"
PREFIX = "jgit-storage-hibernate-"
LAYERS = {
    "jgit-storage-hibernate-core": 0,
    "jgit-storage-hibernate-search": 1,
    "jgit-storage-hibernate-java-analysis": 2,
    "jgit-storage-hibernate-architecture": 3,
}
BENCHMARKS = "jgit-storage-hibernate-benchmarks"
DATABASE_DRIVERS = {
    ("com.h2database", "h2"),
    ("org.hsqldb", "hsqldb"),
    ("org.postgresql", "postgresql"),
    ("com.microsoft.sqlserver", "mssql-jdbc"),
    ("com.mysql", "mysql-connector-j"),
    ("org.mariadb.jdbc", "mariadb-java-client"),
    ("com.oracle.database.jdbc", "ojdbc11"),
}


@dataclass(frozen=True)
class Dependency:
    module: str
    group_id: str
    artifact_id: str
    scope: str
    optional: bool
    dependency_type: str

    @property
    def published_runtime(self) -> bool:
        return self.scope not in {"test", "provided", "system"} and self.dependency_type != "pom"


def text(element: ET.Element | None, name: str, default: str = "") -> str:
    if element is None:
        return default
    value = element.findtext(MAVEN + name)
    return value.strip() if value else default


def root_modules(root: Path) -> list[Path]:
    pom = ET.parse(root / "pom.xml").getroot()
    modules = pom.find(MAVEN + "modules")
    if modules is None:
        raise ValueError("Root pom.xml has no reactor modules")
    result = []
    for module in modules.findall(MAVEN + "module"):
        if module.text and (root / module.text.strip() / "pom.xml").is_file():
            result.append(root / module.text.strip())
    return sorted(result)


def dependencies(module_dir: Path) -> tuple[str, list[Dependency]]:
    pom = ET.parse(module_dir / "pom.xml").getroot()
    artifact = text(pom, "artifactId")
    dependencies_element = pom.find(MAVEN + "dependencies")
    result: list[Dependency] = []
    if dependencies_element is None:
        return artifact, result
    for dependency in dependencies_element.findall(MAVEN + "dependency"):
        result.append(
            Dependency(
                module=artifact,
                group_id=text(dependency, "groupId"),
                artifact_id=text(dependency, "artifactId"),
                scope=text(dependency, "scope", "compile"),
                optional=text(dependency, "optional", "false").lower() == "true",
                dependency_type=text(dependency, "type", "jar"),
            )
        )
    return artifact, result


def cycle(graph: dict[str, set[str]]) -> list[str] | None:
    visiting: list[str] = []
    active: set[str] = set()
    complete: set[str] = set()

    def visit(node: str) -> list[str] | None:
        if node in complete:
            return None
        if node in active:
            start = visiting.index(node)
            return visiting[start:] + [node]
        active.add(node)
        visiting.append(node)
        for target in sorted(graph.get(node, set())):
            found = visit(target)
            if found is not None:
                return found
        visiting.pop()
        active.remove(node)
        complete.add(node)
        return None

    for node in sorted(graph):
        found = visit(node)
        if found is not None:
            return found
    return None


def verify(all_dependencies: list[Dependency]) -> dict[str, object]:
    violations: list[str] = []
    graph: dict[str, set[str]] = {module: set() for module in LAYERS}
    graph[BENCHMARKS] = set()

    for dependency in all_dependencies:
        source = dependency.module
        target = dependency.artifact_id
        sibling = dependency.group_id == GROUP_ID and target.startswith(PREFIX)
        if sibling and dependency.scope != "test":
            graph.setdefault(source, set()).add(target)

        if source in LAYERS and target == BENCHMARKS and dependency.scope != "test":
            violations.append(
                f"{source} must not depend on the benchmark artifact in {dependency.scope} scope"
            )
        if (
            source in LAYERS
            and target in LAYERS
            and dependency.scope != "test"
            and LAYERS[target] > LAYERS[source]
        ):
            violations.append(
                f"{source} (layer {LAYERS[source]}) depends upward on "
                f"{target} (layer {LAYERS[target]}) in {dependency.scope} scope"
            )
        if (
            source == "jgit-storage-hibernate-core"
            and (dependency.group_id, target) in DATABASE_DRIVERS
            and dependency.published_runtime
            and not dependency.optional
        ):
            violations.append(
                f"Core forces database driver {dependency.group_id}:{target} "
                f"in {dependency.scope} scope"
            )
        if (
            source in LAYERS
            and dependency.group_id == "org.springframework.boot"
            and dependency.scope != "test"
        ):
            violations.append(
                f"Published library module {source} depends on Spring Boot application artifact "
                f"{target} in {dependency.scope} scope"
            )

    found_cycle = cycle(graph)
    if found_cycle is not None:
        violations.append("Published module cycle: " + " -> ".join(found_cycle))

    return {
        "layers": LAYERS,
        "graph": {module: sorted(targets) for module, targets in sorted(graph.items())},
        "dependencies": [asdict(item) for item in all_dependencies],
        "violations": violations,
    }


def markdown(report: dict[str, object]) -> str:
    lines = [
        "# jgit-storage-hibernate module capability graph",
        "",
        "| Module | Layer | Non-test sibling dependencies |",
        "|---|---:|---|",
    ]
    graph = report["graph"]
    layers = report["layers"]
    for module in sorted(graph):
        layer = layers.get(module, "development")
        targets = ", ".join(f"`{target}`" for target in graph[module]) or "—"
        lines.append(f"| `{module}` | {layer} | {targets} |")
    lines.extend(["", "## Violations", ""])
    violations = report["violations"]
    if violations:
        lines.extend(f"- {violation}" for violation in violations)
    else:
        lines.append("None.")
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--json-report", type=Path, required=True)
    parser.add_argument("--markdown-report", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    module_dependencies: list[Dependency] = []
    for module_dir in root_modules(args.root):
        _, parsed = dependencies(module_dir)
        module_dependencies.extend(parsed)
    report = verify(module_dependencies)
    args.json_report.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_report.parent.mkdir(parents=True, exist_ok=True)
    args.json_report.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    args.markdown_report.write_text(markdown(report), encoding="utf-8")
    if report["violations"]:
        raise SystemExit("Module boundary violations:\n- " + "\n- ".join(report["violations"]))
    print("Verified consumer-composable module boundaries")


if __name__ == "__main__":
    main()
