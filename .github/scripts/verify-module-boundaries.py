#!/usr/bin/env python3
"""Verify optional-module boundaries and emit a machine-readable reactor dependency graph."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROJECT_GROUP = "io.github.carstenartur"
PREFIX = "jgit-storage-hibernate-"

CORE = PREFIX + "core"
SECURITY = PREFIX + "security"
SEARCH = PREFIX + "search"
JAVA_ANALYSIS = PREFIX + "java-analysis"
ARCHITECTURE = PREFIX + "architecture"
BENCHMARKS = PREFIX + "benchmarks"
BOM = PREFIX + "bom"
PARENT = PREFIX + "parent"

RUNTIME_MODULES = {CORE, SECURITY, SEARCH, JAVA_ANALYSIS, ARCHITECTURE}
ALLOWED_INTERNAL = {
    CORE: set(),
    SECURITY: {CORE},
    SEARCH: {CORE},
    JAVA_ANALYSIS: {CORE},
    ARCHITECTURE: {JAVA_ANALYSIS},
    BENCHMARKS: {CORE, SECURITY, SEARCH, JAVA_ANALYSIS, ARCHITECTURE},
}

DB_DRIVERS = {
    ("org.postgresql", "postgresql"),
    ("com.h2database", "h2"),
    ("org.hsqldb", "hsqldb"),
    ("com.microsoft.sqlserver", "mssql-jdbc"),
}
FORBIDDEN_GROUP_PREFIXES = ("org.springframework", "org.springframework.boot")
FORBIDDEN_UI_TOKENS = ("swt", "jface", "workbench", "e4.ui")
SECURITY_FORBIDDEN_GROUP_PREFIXES = (
    "org.hibernate.search",
    "jakarta.servlet",
    "javax.servlet",
)
SECURITY_FORBIDDEN_COORDINATES = {
    ("org.eclipse.jgit", "org.eclipse.jgit.http.server"),
}
SECURITY_FORBIDDEN_ARTIFACT_TOKENS = (
    "servlet",
    "spring-security",
    "jetty",
    "tomcat",
    "undertow",
)


@dataclass(frozen=True)
class Dependency:
    group_id: str
    artifact_id: str
    scope: str
    optional: bool

    @property
    def production(self) -> bool:
        return self.scope != "test"

    def as_dict(self) -> dict[str, object]:
        return {
            "groupId": self.group_id,
            "artifactId": self.artifact_id,
            "scope": self.scope,
            "optional": self.optional,
            "production": self.production,
        }


@dataclass(frozen=True)
class Module:
    path: str
    artifact_id: str
    packaging: str
    dependencies: tuple[Dependency, ...]


class BoundaryError(RuntimeError):
    pass


def _text(element: ET.Element | None, child: str, default: str = "") -> str:
    if element is None:
        return default
    child_element = element.find(f"m:{child}", NS)
    if child_element is None or child_element.text is None:
        return default
    return child_element.text.strip()


def _parse_module(root: Path, relative: str) -> Module:
    pom = root / relative / "pom.xml"
    document = ET.parse(pom).getroot()
    artifact_id = _text(document, "artifactId")
    if not artifact_id:
        raise BoundaryError(f"Module {relative!r} has no artifactId")
    packaging = _text(document, "packaging", "jar")
    dependencies: list[Dependency] = []
    dependencies_element = document.find("m:dependencies", NS)
    if dependencies_element is not None:
        for dependency in dependencies_element.findall("m:dependency", NS):
            dependencies.append(
                Dependency(
                    group_id=_text(dependency, "groupId"),
                    artifact_id=_text(dependency, "artifactId"),
                    scope=_text(dependency, "scope", "compile"),
                    optional=_text(dependency, "optional", "false").lower() == "true",
                )
            )
    return Module(relative, artifact_id, packaging, tuple(dependencies))


def load_reactor(root: Path) -> dict[str, Module]:
    document = ET.parse(root / "pom.xml").getroot()
    modules_element = document.find("m:modules", NS)
    if modules_element is None:
        raise BoundaryError("Root pom.xml has no <modules> reactor declaration")
    modules: dict[str, Module] = {}
    for element in modules_element.findall("m:module", NS):
        if element.text is None or not element.text.strip():
            continue
        module = _parse_module(root, element.text.strip())
        if module.artifact_id in modules:
            raise BoundaryError(f"Duplicate reactor artifactId {module.artifact_id!r}")
        modules[module.artifact_id] = module
    return modules


def _production_internal_edges(modules: dict[str, Module]) -> dict[str, set[str]]:
    edges = {artifact: set() for artifact in modules}
    for artifact, module in modules.items():
        for dependency in module.dependencies:
            if (
                dependency.production
                and dependency.group_id == PROJECT_GROUP
                and dependency.artifact_id in modules
            ):
                edges[artifact].add(dependency.artifact_id)
    return edges


def _check_cycles(edges: dict[str, set[str]]) -> None:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str, trail: list[str]) -> None:
        if node in visiting:
            cycle_start = trail.index(node)
            cycle = trail[cycle_start:] + [node]
            raise BoundaryError("Production module dependency cycle: " + " -> ".join(cycle))
        if node in visited:
            return
        visiting.add(node)
        trail.append(node)
        for target in sorted(edges.get(node, ())):
            visit(target, trail)
        trail.pop()
        visiting.remove(node)
        visited.add(node)

    for node in sorted(edges):
        visit(node, [])


def _check_internal_boundaries(modules: dict[str, Module], edges: dict[str, set[str]]) -> None:
    # Benchmarks are evidence tooling, never a production capability. Diagnose this before the
    # generic layering rule so the failure explains the architectural reason for the rejection.
    for source, targets in edges.items():
        if BENCHMARKS in targets and source != BENCHMARKS:
            raise BoundaryError(f"{source} must never depend on the benchmark module in production")

    for source, targets in edges.items():
        if source == BOM or modules[source].packaging == "pom":
            continue
        allowed = ALLOWED_INTERNAL.get(source)
        if allowed is None:
            continue
        forbidden = targets - allowed
        if forbidden:
            raise BoundaryError(
                f"{source} has forbidden production module dependencies: {', '.join(sorted(forbidden))}; "
                f"allowed: {', '.join(sorted(allowed)) or 'none'}"
            )


def _check_external_boundaries(modules: dict[str, Module]) -> None:
    violations: list[str] = []
    for artifact, module in modules.items():
        if artifact not in RUNTIME_MODULES:
            continue
        for dependency in module.dependencies:
            if not dependency.production:
                continue
            coordinate = (dependency.group_id, dependency.artifact_id)
            lowered = dependency.artifact_id.lower()
            if coordinate in DB_DRIVERS:
                violations.append(
                    f"{artifact} -> {dependency.group_id}:{dependency.artifact_id} ({dependency.scope}) database driver"
                )
            if dependency.group_id.startswith(FORBIDDEN_GROUP_PREFIXES):
                violations.append(
                    f"{artifact} -> {dependency.group_id}:{dependency.artifact_id} ({dependency.scope}) Spring runtime"
                )
            if any(token in lowered for token in FORBIDDEN_UI_TOKENS):
                violations.append(
                    f"{artifact} -> {dependency.group_id}:{dependency.artifact_id} ({dependency.scope}) UI runtime"
                )
            if artifact == SECURITY and (
                dependency.group_id.startswith(SECURITY_FORBIDDEN_GROUP_PREFIXES)
                or coordinate in SECURITY_FORBIDDEN_COORDINATES
                or any(token in lowered for token in SECURITY_FORBIDDEN_ARTIFACT_TOKENS)
            ):
                violations.append(
                    f"{artifact} -> {dependency.group_id}:{dependency.artifact_id} "
                    f"({dependency.scope}) forbidden Security runtime"
                )
    if violations:
        raise BoundaryError("Forbidden production dependencies:\n- " + "\n- ".join(violations))


def verify(modules: dict[str, Module]) -> dict[str, set[str]]:
    required = {CORE, SECURITY, SEARCH, JAVA_ANALYSIS, ARCHITECTURE, BENCHMARKS, BOM}
    missing = required - set(modules)
    if missing:
        raise BoundaryError("Missing expected reactor modules: " + ", ".join(sorted(missing)))
    edges = _production_internal_edges(modules)
    # Prefer precise architectural diagnostics before the secondary cycle check.
    _check_internal_boundaries(modules, edges)
    _check_external_boundaries(modules)
    _check_cycles(edges)
    return edges


def graph_json(modules: dict[str, Module], edges: dict[str, set[str]]) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "groupId": PROJECT_GROUP,
        "modules": [
            {
                "path": module.path,
                "artifactId": module.artifact_id,
                "packaging": module.packaging,
                "productionModuleDependencies": sorted(edges[module.artifact_id]),
                "dependencies": [dependency.as_dict() for dependency in module.dependencies],
            }
            for module in sorted(modules.values(), key=lambda item: item.artifact_id)
        ],
    }


def graph_markdown(modules: dict[str, Module], edges: dict[str, set[str]]) -> str:
    lines = [
        "# Module boundary evidence",
        "",
        "Generated from the reactor POMs. Production edges exclude only `test` scope.",
        "",
        "| Module | Production module dependencies |",
        "|---|---|",
    ]
    for module in sorted(modules.values(), key=lambda item: item.artifact_id):
        dependencies = ", ".join(f"`{value}`" for value in sorted(edges[module.artifact_id])) or "—"
        lines.append(f"| `{module.artifact_id}` | {dependencies} |")
    lines.extend(
        [
            "",
            "## Enforced rules",
            "",
            "- Core has no production dependency on Security, Search, Java Analysis, Architecture or Benchmarks.",
            "- Security may depend on Core only and never on Search, Servlet, Spring or HTTP runtimes.",
            "- Search may depend on Core only among project runtime modules.",
            "- Java Analysis may depend on Core only among project runtime modules.",
            "- Architecture may depend on Java Analysis only among project runtime modules.",
            "- No production module depends on Benchmarks.",
            "- Runtime modules do not compile against concrete database drivers, Spring runtimes or Eclipse UI runtimes.",
            "- Production project-module dependencies are acyclic.",
            "",
        ]
    )
    return "\n".join(lines)


def run(root: Path, json_output: Path, markdown_output: Path) -> None:
    modules = load_reactor(root)
    edges = verify(modules)
    json_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(json.dumps(graph_json(modules, edges), indent=2) + "\n", encoding="utf-8")
    markdown_output.write_text(graph_markdown(modules, edges), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--json", type=Path, default=Path("target/module-boundaries/module-graph.json"))
    parser.add_argument("--markdown", type=Path, default=Path("target/module-boundaries/module-graph.md"))
    args = parser.parse_args()
    run(args.root.resolve(), args.json, args.markdown)
    print(f"Module boundaries verified: {args.json} / {args.markdown}")


if __name__ == "__main__":
    main()
