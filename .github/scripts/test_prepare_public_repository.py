#!/usr/bin/env python3
"""Regression tests for canonical public Maven repository preparation."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("prepare-public-repository.py")
SPEC = importlib.util.spec_from_file_location("prepare_public_repository", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def main() -> None:
    version = "0.1.10"
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        repository = root / "repository"
        evidence = root / "evidence" / "manifest.json"
        expected: dict[Path, bytes] = {}
        for index, relative in enumerate(MODULE.required(version)):
            path = repository / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            data = f"artifact-{index}\n".encode()
            expected[relative] = data
            path.write_bytes(data)
            path.with_name(path.name + ".sha1").write_text(
                "stale-sha1\n", encoding="ascii"
            )
            path.with_name(path.name + ".md5").write_text(
                "volatile\n", encoding="ascii"
            )
            path.parent.joinpath("_remote.repositories").write_text(
                "volatile\n", encoding="utf-8"
            )
            path.parent.joinpath(path.name + ".lastUpdated").write_text(
                "volatile\n", encoding="utf-8"
            )

        subprocess.run(
            [sys.executable, str(SCRIPT), version, str(repository), str(evidence)],
            check=True,
        )

        volatile = [
            path.relative_to(repository).as_posix()
            for path in repository.rglob("*")
            if path.is_file() and MODULE.is_volatile(path)
        ]
        assert not volatile, f"volatile repository files remain: {volatile}"

        for relative, data in expected.items():
            path = repository / relative
            assert path.is_file()
            expected_digests = {
                "sha1": hashlib.sha1(data, usedforsecurity=False).hexdigest(),
                "sha256": hashlib.sha256(data).hexdigest(),
                "sha512": hashlib.sha512(data).hexdigest(),
            }
            for algorithm, digest in expected_digests.items():
                sidecar = path.with_name(path.name + f".{algorithm}")
                assert sidecar.is_file()
                assert sidecar.read_text(encoding="ascii").strip() == digest

        manifest = json.loads(evidence.read_text(encoding="utf-8"))
        assert manifest["canonicalChecksums"] == ["sha256", "sha512"]
        assert manifest["compatibilityChecksums"] == ["sha1"]
        assert len(manifest["files"]) == len(MODULE.required(version))
        assert manifest["removedVolatileFiles"], (
            "expected volatile files to be reported"
        )
        for item in manifest["files"]:
            assert set(("sha1", "sha256", "sha512")).issubset(item)

    print("Public repository canonicalization regression tests passed")


if __name__ == "__main__":
    main()
