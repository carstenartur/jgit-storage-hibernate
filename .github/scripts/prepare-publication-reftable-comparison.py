#!/usr/bin/env python3
"""Audit and retain two exact core JARs before the bounded comparison."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

BASELINE = "96f1b08d5d0ca32f98508828c78074f12da688fc"
CANDIDATE = "fcd8eb6ba5fa009f50c20f927fe6e932aca6510b"
SOURCE_PATH = "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.java"
CANDIDATE_BLOB = "69da64f8bb579d409ed975e338a35b40cf861f3b"
BASELINE_BLOB = "86b9157087e84b826ac3431250f65912413cd564"
CORE_CLASS = "io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.class"
GUARDED = ["pom.xml", "jgit-storage-hibernate-core/pom.xml", "jgit-storage-hibernate-benchmarks/pom.xml",
           "jgit-storage-hibernate-core/src/main",
           "jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/ConcurrentPublicationBenchmark.java",
           "jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/PackPublicationProfiler.java"]
HARNESS_DEPENDENCIES = ["pom.xml", "jgit-storage-hibernate-core/pom.xml",
                        "jgit-storage-hibernate-benchmarks/pom.xml",
                        "jgit-storage-hibernate-benchmarks/src/main"]


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def jar_entries(path):
    with zipfile.ZipFile(path) as archive:
        items = [item for item in archive.infolist() if not item.is_dir()]
        require(len(items) == len({item.filename for item in items}), "Duplicate JAR entry")
        require(sum(item.file_size for item in items) < 32 * 1024**2, "Unexpected core JAR size")
        entries = {}
        for item in items:
            name = PurePosixPath(item.filename)
            require(not name.is_absolute() and ".." not in name.parts and "\\" not in item.filename,
                    "Unsafe JAR entry")
            entries[item.filename] = sha(archive.read(item))
        require(CORE_CLASS in entries, "Core JAR lacks the primary class")
        return entries


def validate_jar_difference(baseline, candidate):
    require(set(baseline) == set(candidate), "Core JAR entry inventory differs")
    require(baseline.get(CORE_CLASS) != candidate.get(CORE_CLASS), "The primary core class did not change")
    changed = sorted(name for name in baseline if baseline[name] != candidate[name])
    family = CORE_CLASS.removesuffix(".class")
    classes, metadata = [], []
    for name in changed:
        if name == CORE_CLASS or (name.startswith(family + "$") and name.endswith(".class")):
            classes.append(name)
        elif name == "META-INF/MANIFEST.MF" or name == (
                "META-INF/maven/io.github.carstenartur/jgit-storage-hibernate-core/pom.properties"):
            metadata.append(name)
        else:
            raise ValueError(f"Unrelated compiled class/resource changed: {name}")
    return {"classEntries": classes, "metadataEntries": metadata}


def source_identity(root, reference):
    require(not git(root, "diff", "--name-only", "HEAD", "--", *GUARDED),
            "Measured source differs from its committed tree")
    require(not git(root, "ls-files", "--others", "--exclude-standard", "--", *GUARDED),
            "Untracked measured source")
    require(not git(root, "diff", "--name-only", reference, "HEAD", "--", *GUARDED),
            "Measured library differs from its immutable reference")
    blobs = {}
    for line in git(root, "ls-tree", "-r", "HEAD", "--", *GUARDED).splitlines():
        identity, path = line.split("\t", 1)
        mode, kind, blob = identity.split()
        require(kind == "blob" and mode in {"100644", "100755"}, "Unexpected source entry")
        blobs[path] = blob
    return {"sourceCommit": reference, "sourceTree": git(root, "rev-parse", reference + "^{tree}"),
            "buildCheckoutCommit": git(root, "rev-parse", "HEAD"),
            "buildCheckoutTree": git(root, "rev-parse", "HEAD^{tree}"),
            "coreSourceTree": git(root, "rev-parse", "HEAD:jgit-storage-hibernate-core/src/main"),
            "guardedBlobs": blobs}


def core_jar(root):
    version = ET.parse(root / "pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
    require(re.fullmatch(r"[a-zA-Z0-9.-]+", version), "Unexpected project version")
    path = root / "jgit-storage-hibernate-core/target" / f"jgit-storage-hibernate-core-{version}.jar"
    require(path.is_file(), "Built core JAR is missing")
    return path


def verify_harness(root):
    require(not git(root, "diff", "--name-only", "HEAD"),
            "Harness build inputs differ from their committed tree")
    require(not git(root, "ls-files", "--others", "--exclude-standard"),
            "Untracked harness build input")
    require(not git(root, "diff", "--name-only", CANDIDATE, "HEAD", "--", *HARNESS_DEPENDENCIES),
            "Historical comparison requires the declared original dependencies and benchmark")
    return {"sourceCommit": git(root, "rev-parse", "HEAD"),
            "sourceTree": git(root, "rev-parse", "HEAD^{tree}")}


def prepare(baseline_root, candidate_root, harness_root, output):
    harness = verify_harness(harness_root)
    output = output.resolve()
    require(not (output / "variants.json").exists(), "Refusing to overwrite prepared variants")
    variants = {}
    jars = {}
    for name, root, reference in (("baseline", baseline_root, BASELINE),
                                   ("candidate", candidate_root, CANDIDATE)):
        identity = source_identity(root, reference)
        require(identity["buildCheckoutCommit"] == reference, "Core must build from its immutable checkout")
        jar = core_jar(root)
        entries = jar_entries(jar)
        variants[name] = dict(identity, jar=f"inputs/{name}-core.jar", jarSha256=sha(jar.read_bytes()),
                              classSha256=entries[CORE_CLASS], entries=entries)
        jars[name] = jar
    old, new = variants["baseline"]["guardedBlobs"], variants["candidate"]["guardedBlobs"]
    require(set(old) == set(new) and [name for name in old if old[name] != new[name]] == [SOURCE_PATH]
            and new[SOURCE_PATH] == CANDIDATE_BLOB, "Unexpected guarded source difference")
    differences = validate_jar_difference(variants["baseline"]["entries"], variants["candidate"]["entries"])
    (output / "inputs").mkdir(parents=True, exist_ok=True)
    for name, jar in jars.items():
        destination = output / variants[name]["jar"]
        require(not destination.exists(), "Refusing to overwrite a retained core JAR")
        shutil.copyfile(jar, destination)
    result = {"schema": "jgit-reftable-variants/v1", "coreClass": CORE_CLASS,
              "harnessSourceCommit": harness["sourceCommit"],
              "harnessSourceTree": harness["sourceTree"],
              "sourceDifferences": [SOURCE_PATH], "compiledDifferences": differences, "variants": variants}
    (output / "variants.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"sourceDifferences": [SOURCE_PATH], "compiledDifferences": differences,
                      "jarSha256": {name: value["jarSha256"] for name, value in variants.items()}}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-source", type=Path)
    parser.add_argument("--candidate-source", type=Path)
    parser.add_argument("--harness-source", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-harness-only", action="store_true")
    args = parser.parse_args()
    if args.verify_harness_only:
        print(json.dumps(verify_harness(args.harness_source.resolve())))
    else:
        require(args.baseline_source and args.candidate_source and args.output, "Both core sources and output are required")
        prepare(args.baseline_source.resolve(), args.candidate_source.resolve(), args.harness_source.resolve(), args.output)
