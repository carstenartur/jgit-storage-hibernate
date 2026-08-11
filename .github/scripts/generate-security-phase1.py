#!/usr/bin/env python3
"""Generate the reviewed phase-1 security capability, then remove this bootstrap in CI."""

from __future__ import annotations

import base64
import io
from pathlib import Path
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE_PATH = Path(__file__).with_name("security-phase1-module.b64")


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    occurrences = text.count(old)
    if occurrences != 1:
        raise RuntimeError(
            f"Expected exactly one occurrence in {relative}, found {occurrences}: {old[:100]!r}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


def extract_module() -> None:
    payload = base64.b64decode("".join(ARCHIVE_PATH.read_text(encoding="ascii").split()))
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        members = archive.getmembers()
        for member in members:
            destination = (ROOT / member.name).resolve()
            if ROOT.resolve() not in destination.parents and destination != ROOT.resolve():
                raise RuntimeError(f"Archive path escapes repository: {member.name}")
        archive.extractall(ROOT, filter="data")


def patch_reactor_and_bom() -> None:
    replace_once(
        "pom.xml",
        """    <module>jgit-storage-hibernate-core</module>
    <module>jgit-storage-hibernate-search</module>""",
        """    <module>jgit-storage-hibernate-core</module>
    <module>jgit-storage-hibernate-security</module>
    <module>jgit-storage-hibernate-search</module>""",
    )
    replace_once(
        "pom.xml",
        """      <dependency>
        <groupId>io.github.carstenartur</groupId>
        <artifactId>jgit-storage-hibernate-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.github.carstenartur</groupId>
        <artifactId>jgit-storage-hibernate-search</artifactId>""",
        """      <dependency>
        <groupId>io.github.carstenartur</groupId>
        <artifactId>jgit-storage-hibernate-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.github.carstenartur</groupId>
        <artifactId>jgit-storage-hibernate-security</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.github.carstenartur</groupId>
        <artifactId>jgit-storage-hibernate-search</artifactId>""",
    )
    replace_once(
        "jgit-storage-hibernate-bom/pom.xml",
        """      <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>jgit-storage-hibernate-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>jgit-storage-hibernate-search</artifactId>""",
        """      <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>jgit-storage-hibernate-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>jgit-storage-hibernate-security</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>jgit-storage-hibernate-search</artifactId>""",
    )
    replace_once(
        "jgit-storage-hibernate-benchmarks/pom.xml",
        """    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-search</artifactId>
    </dependency>""",
        """    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-search</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-security</artifactId>
      <scope>test</scope>
    </dependency>""",
    )


def patch_boundaries() -> None:
    path = ".github/scripts/verify-module-boundaries.py"
    replace_once(
        path,
        'CORE = PREFIX + "core"\nSEARCH = PREFIX + "search"',
        'CORE = PREFIX + "core"\nSECURITY = PREFIX + "security"\nSEARCH = PREFIX + "search"',
    )
    replace_once(
        path,
        "RUNTIME_MODULES = {CORE, SEARCH, JAVA_ANALYSIS, ARCHITECTURE}",
        "RUNTIME_MODULES = {CORE, SECURITY, SEARCH, JAVA_ANALYSIS, ARCHITECTURE}",
    )
    replace_once(
        path,
        """ALLOWED_INTERNAL = {
    CORE: set(),
    SEARCH: {CORE},
    JAVA_ANALYSIS: {CORE},
    ARCHITECTURE: {JAVA_ANALYSIS},
    BENCHMARKS: {CORE, SEARCH, JAVA_ANALYSIS, ARCHITECTURE},
}""",
        """ALLOWED_INTERNAL = {
    CORE: set(),
    SECURITY: {CORE},
    SEARCH: {CORE},
    JAVA_ANALYSIS: {CORE},
    ARCHITECTURE: {JAVA_ANALYSIS},
    BENCHMARKS: {CORE, SECURITY, SEARCH, JAVA_ANALYSIS, ARCHITECTURE},
}""",
    )
    replace_once(
        path,
        'FORBIDDEN_UI_TOKENS = ("swt", "jface", "workbench", "e4.ui")',
        '''FORBIDDEN_UI_TOKENS = ("swt", "jface", "workbench", "e4.ui")
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
)''',
    )
    replace_once(
        path,
        '''            if any(token in lowered for token in FORBIDDEN_UI_TOKENS):
                violations.append(
                    f"{artifact} -> {dependency.group_id}:{dependency.artifact_id} ({dependency.scope}) UI runtime"
                )''',
        '''            if any(token in lowered for token in FORBIDDEN_UI_TOKENS):
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
                )''',
    )
    replace_once(
        path,
        "required = {CORE, SEARCH, JAVA_ANALYSIS, ARCHITECTURE, BENCHMARKS, BOM}",
        "required = {CORE, SECURITY, SEARCH, JAVA_ANALYSIS, ARCHITECTURE, BENCHMARKS, BOM}",
    )
    replace_once(
        path,
        '"- Core has no production dependency on Search, Java Analysis, Architecture or Benchmarks.",\n            "- Search may depend on Core only among project runtime modules.",',
        '"- Core has no production dependency on Security, Search, Java Analysis, Architecture or Benchmarks.",\n            "- Security may depend on Core only and never on Search, Servlet, Spring or HTTP runtimes.",\n            "- Search may depend on Core only among project runtime modules.",',
    )

    test = ".github/scripts/test_verify_module_boundaries.py"
    replace_once(
        test,
        """        MODULE.CORE: module(MODULE.CORE),
        MODULE.SEARCH: module(MODULE.SEARCH, dep(MODULE.CORE)),""",
        """        MODULE.CORE: module(MODULE.CORE),
        MODULE.SECURITY: module(MODULE.SECURITY, dep(MODULE.CORE)),
        MODULE.SEARCH: module(MODULE.SEARCH, dep(MODULE.CORE)),""",
    )
    replace_once(
        test,
        """        self.assertEqual(set(), edges[MODULE.CORE])
        self.assertEqual({MODULE.CORE}, edges[MODULE.SEARCH])""",
        """        self.assertEqual(set(), edges[MODULE.CORE])
        self.assertEqual({MODULE.CORE}, edges[MODULE.SECURITY])
        self.assertEqual({MODULE.CORE}, edges[MODULE.SEARCH])""",
    )
    replace_once(
        test,
        """    def test_rejects_runtime_dependency_on_benchmarks(self) -> None:
        modules = valid_modules()""",
        """    def test_rejects_security_dependency_on_search_or_protocol_runtime(self) -> None:
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
        modules = valid_modules()""",
    )


def patch_publication_contract() -> None:
    for relative in (
        ".github/scripts/verify-bom-contract.py",
        ".github/scripts/verify-public-repository-publishing.py",
    ):
        replace_once(
            relative,
            '    "jgit-storage-hibernate-core",\n    "jgit-storage-hibernate-search",',
            '    "jgit-storage-hibernate-core",\n    "jgit-storage-hibernate-security",\n    "jgit-storage-hibernate-search",',
        )
    replace_once(
        ".github/scripts/prepare-public-repository.py",
        '    "jgit-storage-hibernate-core",\n    "jgit-storage-hibernate-search",',
        '    "jgit-storage-hibernate-core",\n    "jgit-storage-hibernate-security",\n    "jgit-storage-hibernate-search",',
    )
    replace_once(
        ".github/public-repository-consumer/pom.xml",
        """    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-search</artifactId>""",
        """    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-security</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-search</artifactId>""",
    )
    replace_once(
        ".github/scripts/verify-public-repository-consumption.sh",
        "for artifact in jgit-storage-hibernate-parent jgit-storage-hibernate-core jgit-storage-hibernate-search",
        "for artifact in jgit-storage-hibernate-parent jgit-storage-hibernate-core jgit-storage-hibernate-security jgit-storage-hibernate-search",
    )
    replace_once(
        ".github/scripts/test_verify_public_repository_consumption.py",
        '                            "jgit-storage-hibernate-core",\n                            "jgit-storage-hibernate-search",',
        '                            "jgit-storage-hibernate-core",\n                            "jgit-storage-hibernate-security",\n                            "jgit-storage-hibernate-search",',
    )
    replace_once(
        "jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/PackagedRuntimeJarLinkageTest.java",
        '          "jgit-storage-hibernate-core",\n          "jgit-storage-hibernate-search",',
        '          "jgit-storage-hibernate-core",\n          "jgit-storage-hibernate-security",\n          "jgit-storage-hibernate-search",',
    )


def patch_documentation() -> None:
    replace_once(
        "README.md",
        """| `jgit-storage-hibernate-core` | You need database-backed Git semantics and transaction-safe repository publication. | Versioned Flyway migrations for H2, HSQLDB and PostgreSQL |
| `jgit-storage-hibernate-search`""",
        """| `jgit-storage-hibernate-core` | You need database-backed Git semantics and transaction-safe repository publication. | Versioned Flyway migrations for H2, HSQLDB, PostgreSQL and SQL Server |
| `jgit-storage-hibernate-security` | You need an optional framework-neutral principal/group ACL model and deterministic protected-ref decisions. | Versioned Flyway migrations for H2, HSQLDB, PostgreSQL and SQL Server; direct-JGit enforcement follows in phase 2 |
| `jgit-storage-hibernate-search`""",
    )
    replace_once(
        "README.md",
        "Add `jgit-storage-hibernate-search` at the same version when the persistent generic query layer is needed.",
        "Add `jgit-storage-hibernate-security` for the optional principal/group ACL schema and evaluator, and add `jgit-storage-hibernate-search` when the persistent generic query layer is needed.",
    )
    replace_once(
        "README.md",
        """| Core | yes | yes | yes | `jgit_storage_hibernate_core_schema_history` |
| Search | yes | no | yes | `jgit_storage_hibernate_search_schema_history` |""",
        """| Core | yes | yes | yes | `jgit_storage_hibernate_core_schema_history` |
| Security | yes | yes | yes | `jgit_storage_hibernate_security_schema_history` |
| Search | yes | no | yes | `jgit_storage_hibernate_search_schema_history` |""",
    )
    replace_once(
        "jgit-storage-hibernate-bom/README.md",
        """- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-search`""",
        """- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-security`
- `io.github.carstenartur:jgit-storage-hibernate-search`""",
    )
    replace_once(
        "jgit-storage-hibernate-bom/README.md",
        """Use `jgit-storage-hibernate-core` for packs, objects, refs, reflogs and transactions without indexed history. The application still selects its JDBC driver explicitly.

Add `jgit-storage-hibernate-search`""",
        """Use `jgit-storage-hibernate-core` for packs, objects, refs, reflogs and transactions without indexed history. The application still selects its JDBC driver explicitly.

Add `jgit-storage-hibernate-security` for the optional framework-neutral principal/group ACL schema and deterministic repository/ref evaluator. It depends on Core and Hibernate ORM, not Hibernate Search, Spring or Servlet APIs.

Add `jgit-storage-hibernate-search`""",
    )
    replace_once(
        "docs/release-process.md",
        """- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-search`""",
        """- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-security`
- `io.github.carstenartur:jgit-storage-hibernate-search`""",
    )
    replace_once(
        "docs/consuming.md",
        """io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-search""",
        """io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-security
io.github.carstenartur:jgit-storage-hibernate-search""",
    )
    replace_once(
        "docs/consuming.md",
        "Core provides database-backed JGit repositories. Search is optional",
        "Core provides database-backed JGit repositories. Security is optional and adds a framework-neutral principal/group ACL schema plus deterministic repository/ref decisions. Search is optional",
    )
    replace_once(
        "docs/consuming.md",
        """Use Search 0.1.16 or later for SQL Server.

## Schema ownership""",
        """Use Search 0.1.16 or later for SQL Server.

Optional database-backed security policy:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-security</artifactId>
  <version>0.11.0-SNAPSHOT</version>
</dependency>
```

Phase 1 supplies the explicit access context, Git-generic permission model, deterministic evaluator and migrations. Principal-bound direct-JGit enforcement is delivered separately so Core-only consumers remain unchanged.

## Schema ownership""",
    )
    replace_once(
        "docs/consuming.md",
        """| Core | `git_packs`, `git_pack_chunks`, `git_repository_lock`, `git_reflog` | `jgit_storage_hibernate_core_schema_history` |
| Search | `git_commit_index` | `jgit_storage_hibernate_search_schema_history` |""",
        """| Core | `git_packs`, `git_pack_chunks`, `git_repository_lock`, `git_reflog` | `jgit_storage_hibernate_core_schema_history` |
| Security | principals, groups, memberships, repository grants, ref rules and monotonic security versions | `jgit_storage_hibernate_security_schema_history` |
| Search | `git_commit_index` | `jgit_storage_hibernate_search_schema_history` |""",
    )
    replace_once(
        "docs/consuming.md",
        """| Search | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/search/sqlserver` |

The public constants in `CoreSchemaMigrations` and `SearchSchemaMigrations`""",
        """| Search | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/search/sqlserver` |
| Security | H2 | `classpath:db/migration/jgit-storage-hibernate/security/h2` |
| Security | HSQLDB | `classpath:db/migration/jgit-storage-hibernate/security/hsqldb` |
| Security | PostgreSQL | `classpath:db/migration/jgit-storage-hibernate/security/postgresql` |
| Security | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/security/sqlserver` |

The public constants in `CoreSchemaMigrations`, `SecuritySchemaMigrations` and `SearchSchemaMigrations`""",
    )


def verify_xml() -> None:
    for path in ROOT.rglob("pom.xml"):
        ET.parse(path)


def main() -> None:
    extract_module()
    patch_reactor_and_bom()
    patch_boundaries()
    patch_publication_contract()
    patch_documentation()
    verify_xml()
    print("Security phase 1 generated and reactor contracts patched")


if __name__ == "__main__":
    main()
