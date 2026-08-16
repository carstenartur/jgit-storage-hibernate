#!/usr/bin/env python3
"""Regression tests for verify-module-boundaries.py."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
SCRIPT = SCRIPT_DIR / "verify-module-boundaries.py"
SPEC = importlib.util.spec_from_file_location("verify_module_boundaries", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def dep(
    artifact: str,
    scope: str = "compile",
    group: str = MODULE.PROJECT_GROUP,
    optional: bool = False,
):
    return MODULE.Dependency(group, artifact, scope, optional)


def module(artifact: str, *dependencies, packaging: str = "jar"):
    return MODULE.Module(artifact, artifact, packaging, tuple(dependencies))


def valid_modules():
    return {
        MODULE.CORE: module(MODULE.CORE),
        MODULE.SECURITY: module(MODULE.SECURITY, dep(MODULE.CORE)),
        MODULE.SMART_HTTP: module(
            MODULE.SMART_HTTP,
            dep(MODULE.CORE),
            dep(MODULE.SECURITY, optional=True),
            dep("org.eclipse.jgit.http.server", group="org.eclipse.jgit"),
            dep("jakarta.servlet-api", group="jakarta.servlet"),
        ),
        MODULE.SEARCH: module(MODULE.SEARCH, dep(MODULE.CORE)),
        MODULE.JAVA_ANALYSIS: module(MODULE.JAVA_ANALYSIS, dep(MODULE.CORE)),
        MODULE.ARCHITECTURE: module(MODULE.ARCHITECTURE, dep(MODULE.JAVA_ANALYSIS)),
        MODULE.BENCHMARKS: module(
            MODULE.BENCHMARKS,
            dep(MODULE.CORE),
            dep(MODULE.SEARCH),
            dep(MODULE.JAVA_ANALYSIS, "test"),
            dep(MODULE.ARCHITECTURE, "test"),
        ),
        MODULE.BOM: module(MODULE.BOM, packaging="pom"),
    }


class ModuleBoundaryVerifierTest(unittest.TestCase):

    def test_accepts_intended_optional_module_direction(self) -> None:
        modules = valid_modules()
        edges = MODULE.verify(modules)
        self.assertEqual(set(), edges[MODULE.CORE])
        self.assertEqual({MODULE.CORE}, edges[MODULE.SECURITY])
        self.assertEqual({MODULE.CORE, MODULE.SECURITY}, edges[MODULE.SMART_HTTP])
        self.assertEqual({MODULE.CORE}, edges[MODULE.SEARCH])
        self.assertEqual({MODULE.CORE}, edges[MODULE.JAVA_ANALYSIS])
        self.assertEqual({MODULE.JAVA_ANALYSIS}, edges[MODULE.ARCHITECTURE])

    def test_rejects_core_dependency_on_optional_search(self) -> None:
        modules = valid_modules()
        modules[MODULE.CORE] = module(MODULE.CORE, dep(MODULE.SEARCH))
        with self.assertRaisesRegex(MODULE.BoundaryError, "forbidden production module dependencies"):
            MODULE.verify(modules)

    def test_requires_smart_http_security_dependency_to_remain_optional(self) -> None:
        modules = valid_modules()
        modules[MODULE.SMART_HTTP] = module(
            MODULE.SMART_HTTP, dep(MODULE.CORE), dep(MODULE.SECURITY)
        )
        with self.assertRaisesRegex(MODULE.BoundaryError, "optional dependency"):
            MODULE.verify(modules)

    def test_rejects_security_dependency_on_search_or_protocol_runtime(self) -> None:
        modules = valid_modules()
        modules[MODULE.SECURITY] = module(
            MODULE.SECURITY, dep(MODULE.CORE), dep(MODULE.SEARCH)
        )
        with self.assertRaisesRegex(
            MODULE.BoundaryError, "forbidden production module dependencies"
        ):
            MODULE.verify(modules)

        for dependency in (
            dep("hibernate-search-mapper-orm", group="org.hibernate.search"),
            dep("jakarta.servlet-api", group="jakarta.servlet"),
            dep("org.eclipse.jgit.http.server", group="org.eclipse.jgit"),
        ):
            with self.subTest(dependency=dependency):
                modules = valid_modules()
                modules[MODULE.SECURITY] = module(
                    MODULE.SECURITY, dep(MODULE.CORE), dependency
                )
                with self.assertRaisesRegex(
                    MODULE.BoundaryError, "forbidden Security runtime"
                ):
                    MODULE.verify(modules)

    def test_rejects_runtime_dependency_on_benchmarks(self) -> None:
        modules = valid_modules()
        modules[MODULE.SEARCH] = module(MODULE.SEARCH, dep(MODULE.CORE), dep(MODULE.BENCHMARKS))
        with self.assertRaisesRegex(MODULE.BoundaryError, "benchmark module"):
            MODULE.verify(modules)

    def test_rejects_production_database_driver_but_allows_test_driver(self) -> None:
        modules = valid_modules()
        modules[MODULE.CORE] = module(MODULE.CORE, dep("postgresql", group="org.postgresql"))
        with self.assertRaisesRegex(MODULE.BoundaryError, "database driver"):
            MODULE.verify(modules)

        modules = valid_modules()
        modules[MODULE.CORE] = module(
            MODULE.CORE, dep("postgresql", "test", group="org.postgresql")
        )
        MODULE.verify(modules)

    def test_rejects_spring_and_eclipse_ui_runtime_leaks(self) -> None:
        modules = valid_modules()
        modules[MODULE.JAVA_ANALYSIS] = module(
            MODULE.JAVA_ANALYSIS,
            dep(MODULE.CORE),
            dep("spring-context", group="org.springframework"),
        )
        with self.assertRaisesRegex(MODULE.BoundaryError, "Spring runtime"):
            MODULE.verify(modules)

        modules = valid_modules()
        modules[MODULE.JAVA_ANALYSIS] = module(
            MODULE.JAVA_ANALYSIS,
            dep(MODULE.CORE),
            dep("org.eclipse.jface", group="org.eclipse.platform"),
        )
        with self.assertRaisesRegex(MODULE.BoundaryError, "UI runtime"):
            MODULE.verify(modules)

    def test_detects_project_module_cycles(self) -> None:
        with self.assertRaisesRegex(MODULE.BoundaryError, "dependency cycle"):
            MODULE._check_cycles({"a": {"b"}, "b": {"c"}, "c": {"a"}})

    def test_evidence_contains_dependencies_and_rules(self) -> None:
        modules = valid_modules()
        edges = MODULE.verify(modules)
        payload = MODULE.graph_json(modules, edges)
        self.assertEqual(1, payload["schemaVersion"])
        self.assertEqual(MODULE.PROJECT_GROUP, payload["groupId"])
        markdown = MODULE.graph_markdown(modules, edges)
        self.assertIn("Core has no production dependency", markdown)
        self.assertIn(MODULE.SMART_HTTP, markdown)
        self.assertIn(MODULE.ARCHITECTURE, markdown)


if __name__ == "__main__":
    unittest.main()
