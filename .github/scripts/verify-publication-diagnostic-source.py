#!/usr/bin/env python3
"""Pin the original diagnostic or its one reviewed reftable counterfactual."""

import argparse
from pathlib import Path
import subprocess

BASELINE = "5ed8aad0f0691bcecb414b319235b67dc0520581"
REFTABLE_PATH = "jgit-storage-hibernate-core/src/main/java/io/github/carstenartur/jgit/storage/hibernate/objects/ReadAheadHibernateObjDatabase.java"
REFTABLE_BLOB = "69da64f8bb579d409ed975e338a35b40cf861f3b"
GUARDED_PATHS = [
    "pom.xml", "jgit-storage-hibernate-core/pom.xml", "jgit-storage-hibernate-benchmarks/pom.xml",
    "jgit-storage-hibernate-core/src/main",
    "jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/ConcurrentPublicationBenchmark.java",
    "jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/PackPublicationProfiler.java",
]


def validate_difference(variant, changed, reftable_blob):
    if variant == "original":
        if changed:
            raise ValueError("Original diagnostic requires unchanged guarded source")
    elif variant == "reftable":
        if changed != [REFTABLE_PATH] or reftable_blob != REFTABLE_BLOB:
            raise ValueError("Counterfactual permits only the exact reviewed reftable source blob")
    else:
        raise ValueError("Unknown diagnostic variant")


def verify(variant, output):
    dirty = subprocess.check_output(
        ["git", "diff", "--name-only", "HEAD", "--", *GUARDED_PATHS], text=True).splitlines()
    if dirty:
        raise ValueError("Measured source must match its committed tree")
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "--", *GUARDED_PATHS], text=True).splitlines()
    if untracked:
        raise ValueError("Measured source must not contain untracked files")
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", BASELINE, "HEAD", "--", *GUARDED_PATHS], text=True).splitlines()
    reftable_blob = subprocess.check_output(["git", "hash-object", REFTABLE_PATH], text=True).strip()
    validate_difference(variant, changed, reftable_blob)
    inventory = subprocess.check_output(["git", "ls-tree", "-r", "HEAD", "--", *GUARDED_PATHS])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(inventory)
    print(f"Validated {variant} diagnostic source against {BASELINE}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("original", "reftable"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    verify(args.variant, args.output)
