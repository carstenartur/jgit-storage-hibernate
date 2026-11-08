#!/usr/bin/env python3
"""Check a local Maven Central bundle without uploading or extracting it."""

import argparse
import hashlib
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


def reactor(root):
    """Read this repository's explicit reactor coordinates, including POM modules."""
    modules, seen = [], set()

    def visit(pom):
        pom = pom.resolve()
        if pom in seen:
            raise ValueError(f"Repeated reactor module: {pom}")
        seen.add(pom)
        project = ET.parse(pom).getroot()
        for element in project.iter():
            element.tag = element.tag.rsplit("}", 1)[-1]
        def value(name, default=""):
            return (project.findtext(name) or default).strip()
        group = value("groupId", value("parent/groupId"))
        artifact = value("artifactId")
        version = value("version", value("parent/version"))
        if "SNAPSHOT" in version.upper():
            raise ValueError(f"SNAPSHOT version is not a Central release: {pom}")
        if not all(re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", v)
                   and ".." not in v for v in (group, artifact, version)):
            raise ValueError(f"Missing or unresolved Maven coordinates: {pom}")
        packaging = value("packaging", "jar")
        if packaging not in ("pom", "jar"):
            raise ValueError(f"Unsupported packaging {packaging}: {pom}")
        stem = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"
        modules.append((stem, packaging))
        for child in project.findall("modules/module"):
            visit(pom.parent / child.text.strip() / "pom.xml")

    visit(root / "pom.xml")
    return modules


def verify(bundle, root, signatures):
    modules = reactor(root)
    expected = set()
    patterns = []
    for stem, packaging in modules:
        expected.add(stem + ".pom")
        if packaging == "jar":
            expected.update(stem + suffix for suffix in (".jar", "-sources.jar", "-javadoc.jar"))
        patterns.append(re.escape(stem) + r"(?:-[A-Za-z0-9][A-Za-z0-9_.-]*)?\.(?:jar|pom)")
    artifact_pattern = re.compile("(?:" + "|".join(patterns) + ")$")
    with zipfile.ZipFile(bundle) as archive:
        entries, seen = {}, set()
        for entry in archive.infolist():
            name = entry.filename
            parts = name.rstrip("/").split("/")
            if (name in seen or "\\" in name or any(p in ("", ".", "..") for p in parts)
                    or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError(f"Duplicate or unsafe ZIP path: {name}")
            seen.add(name)
            if entry.is_dir():
                continue
            base = re.sub(r"\.(?:md5|sha1|sha256|sha512)$", "", name)
            base = base.removesuffix(".asc")
            if not artifact_pattern.fullmatch(base):
                raise ValueError(f"Unexpected bundle path: {name}")
            entries[name] = entry
        for name in sorted(expected):
            if name not in entries:
                raise ValueError(f"Missing reactor artifact: {name}")
        artifacts = sorted(name for name in entries if name.endswith((".jar", ".pom")))
        for name in artifacts:
            for suffix in (".asc", ".md5", ".sha1"):
                if name + suffix not in entries:
                    raise ValueError(f"Missing sidecar: {name + suffix}")
            if not archive.read(entries[name + ".asc"]).strip():
                raise ValueError(f"Empty signature: {name}.asc")
            if signatures:
                with tempfile.TemporaryDirectory(prefix="central-signature-") as directory:
                    payload = Path(directory) / "artifact"
                    signature = Path(directory) / "artifact.asc"
                    payload.write_bytes(archive.read(entries[name]))
                    signature.write_bytes(archive.read(entries[name + ".asc"]))
                    result = subprocess.run(["gpg", "--no-options", "--batch", "--no-auto-key-retrieve",
                                             "--verify", str(signature), str(payload)],
                                            capture_output=True, text=True, timeout=30)
                    if result.returncode:
                        raise ValueError(f"Invalid signature for {name}: {result.stderr.strip()}")
        for name in entries:
            payload, _, suffix = name.rpartition(".")
            if suffix not in ("asc", "md5", "sha1", "sha256", "sha512"):
                continue
            if payload not in entries:
                raise ValueError(f"Orphan sidecar: {name}")
            if suffix != "asc":
                actual = archive.read(entries[name]).decode("ascii").strip().lower()
                if actual != hashlib.new(suffix, archive.read(entries[payload])).hexdigest():
                    raise ValueError(f"Invalid checksum: {name}")
    print(f"Verified {len(artifacts)} artifacts for {len(modules)} reactor modules" +
          (" and their GPG signatures" if signatures else " (signature presence only)"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--project-root", type=Path, default=Path.cwd())
    parser.add_argument("--verify-signatures", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.bundle, args.project_root, args.verify_signatures)
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(f"Central bundle verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
