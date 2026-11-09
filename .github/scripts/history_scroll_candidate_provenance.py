#!/usr/bin/env python3
"""Provenance for the pinned #230 Search comparison; never substitutes for its native tests."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET


ARTIFACTS = ("mapper-orm", "mapper-pojo-base", "engine", "util-common", "backend-lucene")
SCROLL_CLASS = "org.hibernate.search.backend.lucene.search.query.impl.LuceneSearchScrollImpl"
VERSIONS = {"base": "8.4.1.Final", "candidate": "8.4.2-SNAPSHOT"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identity(root, expected, prefix, result):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

    result[prefix + "Commit"] = git("rev-parse", "HEAD")
    result[prefix + "Tree"] = git("rev-parse", "HEAD^{tree}")
    require(result[prefix + "Commit"] == expected, f"Unexpected {prefix} commit")
    require(not git("status", "--porcelain", "--untracked-files=no"),
            f"Tracked {prefix} source changed after checkout")


def jar_record(path):
    require(path.is_file(), f"Missing runtime JAR: {path}")
    return {"path": str(path.resolve()), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def search_paths(classpath, version):
    paths = [Path(entry).resolve() for entry in classpath.strip().split(os.pathsep)
             if Path(entry).name.startswith("hibernate-search-")]
    expected = {f"hibernate-search-{artifact}-{version}.jar" for artifact in ARTIFACTS}
    require(len(paths) == len(expected) and {path.name for path in paths} == expected,
            "Missing, duplicated or unexpected Search runtime JARs")
    return set(paths)


def record_source(args, result):
    identity(args.source_root, args.commit, "source", result)
    for name in ("extensions.xml", "develocity.xml"):
        require(not (args.source_root / ".mvn" / name).exists(),
                f"Optional telemetry must remain absent: {name}")
    require(args.version == VERSIONS["candidate"] and
            ET.parse(args.source_root / "pom.xml").getroot().findtext("{*}version") == args.version,
            "Candidate source version does not match the installed version")
    jars = {}
    result["searchJars"] = jars
    for artifact in ARTIFACTS:
        name = "hibernate-search-" + artifact
        path = args.maven_repo / "org/hibernate/search" / name / args.version / f"{name}-{args.version}.jar"
        jars[path.name] = jar_record(path)


def record_runtime(args, result):
    identity(args.application_root, args.commit, "application", result)
    require(args.version == VERSIONS[args.variant], "Unexpected comparison version")
    method = {"postgresql": "scrollingContractWorksOnPostgreSql",
              "sqlserver": "scrollingContractWorksOnSqlServer"}[args.backend]
    expected = {"HistoryScrollExportBenchmarkIT": "comparesScrollWithOffsetOnTheSelectedDatabase",
                "CommitSearchScrollingDatabaseIntegrationTest": method,
                "CommitSearchScrollingH2Test": None}
    suites, actual_paths = {}, None
    result["tests"] = suites
    for path in sorted((args.artifacts / "surefire-reports").glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        name = suite.attrib["name"].rsplit(".", 1)[-1]
        if name not in expected:
            continue
        require(name not in suites, f"Duplicated native suite: {name}")
        cases = suite.findall("testcase")
        methods = [case.attrib["name"] for case in cases]
        suites[name] = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        suites[name]["methods"] = methods
        require(suites[name]["tests"] == len(cases) > 0 and
                all(suites[name][key] == 0 for key in ("failures", "errors", "skipped")) and
                all(case.find(tag) is None for case in cases for tag in ("failure", "error", "skipped")),
                f"Native suite failed, skipped or incomplete: {name}")
        require(expected[name] is None or expected[name] in methods, f"Native method not executed: {name}")
        properties = {p.attrib["name"]: p.attrib["value"] for p in suite.findall("properties/property")}
        require(properties.get("java.specification.version") == "21", "Native JVM must be Java 21")
        classpath = properties["surefire.test.class.path"]
        orm = [Path(entry).name for entry in classpath.split(os.pathsep)
               if Path(entry).name.startswith("hibernate-core-")]
        require(orm == ["hibernate-core-7.4.14.Final.jar"], "Native ORM version changed")
        paths = search_paths(classpath, args.version)
        require(actual_paths is None or paths == actual_paths, "Native suites used different Search JARs")
        actual_paths = paths
    require(suites.keys() == expected.keys(), "Missing native Surefire suite")
    result["jdk"] = "21"
    result["ormVersion"] = "7.4.14.Final"
    resolved = search_paths((args.artifacts / "runtime-classpath.txt").read_text(), args.version)
    require(actual_paths == resolved, "Resolved classpath differs from executed Surefire classpath")
    coordinates = set(re.findall(r"org\.hibernate\.search:([^:\s]+):jar:([^:\s]+):",
                                 (args.artifacts / "dependency-tree.txt").read_text()))
    require(coordinates == {(f"hibernate-search-{name}", args.version) for name in ARTIFACTS},
            "Dependency tree differs from executed Search runtime")
    jars = {path.name: jar_record(path) for path in sorted(actual_paths)}
    result["searchJars"] = jars

    loaded = set()
    for path in sorted(args.artifacts.glob("class-load-*.log")):
        for uri in re.findall(re.escape(SCROLL_CLASS) + r" source: (\S+)", path.read_text()):
            url = urlparse(uri)
            require(url.scheme == "file" and not url.netloc, "Unexpected scroll class-load source")
            loaded.add(Path(unquote(url.path)).resolve())
    result["loadedScrollSources"] = sorted(map(str, loaded))
    backend = next(path for path in actual_paths if path.name.startswith("hibernate-search-backend-lucene-"))
    require(loaded == {backend}, "Actual Lucene scroll class-load source differs from the runtime classpath")
    if args.variant == "candidate":
        require(args.source_manifest is not None, "Missing candidate source manifest")
        source = json.loads(args.source_manifest.read_text())
        require(source["status"] == "validated" and source["searchVersion"] == args.version,
                "Candidate source build was not validated")
        result["searchSourceCommit"] = source["sourceCommit"]
        result["searchSourceTree"] = source["sourceTree"]
        require({name: jar["sha256"] for name, jar in jars.items()} ==
                {name: jar["sha256"] for name, jar in source["searchJars"].items()},
                "Executed candidate JARs differ from the recorded source build")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    source = commands.add_parser("source")
    source.add_argument("--source-root", type=Path, required=True)
    source.add_argument("--maven-repo", type=Path, required=True)
    source.add_argument("--output", type=Path, required=True)
    runtime = commands.add_parser("runtime")
    runtime.add_argument("--application-root", type=Path, required=True)
    runtime.add_argument("--artifacts", type=Path, required=True)
    runtime.add_argument("--variant", choices=VERSIONS, required=True)
    runtime.add_argument("--backend", choices=("postgresql", "sqlserver"), required=True)
    runtime.add_argument("--source-manifest", type=Path)
    for command in (source, runtime):
        command.add_argument("--commit", required=True)
        command.add_argument("--version", required=True)
    args = parser.parse_args()
    output = args.output if args.command == "source" else args.artifacts / "provenance.json"
    result = {"schemaVersion": 1, "mode": args.command, "expectedCommit": args.commit,
              "searchVersion": args.version}
    try:
        require(re.fullmatch(r"[0-9a-f]{40}", args.commit), "Require an exact source commit")
        if args.command == "source":
            record_source(args, result)
        else:
            result.update(variant=args.variant, backend=args.backend)
            record_runtime(args, result)
        result["status"] = "validated"
    except (OSError, ValueError, KeyError, ET.ParseError, subprocess.CalledProcessError) as error:
        result.update(status="failed", error=str(error))
        print(str(error), file=sys.stderr)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    return 0 if result["status"] == "validated" else 1


if __name__ == "__main__":
    sys.exit(main())
