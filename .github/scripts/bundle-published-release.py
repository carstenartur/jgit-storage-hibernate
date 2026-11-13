#!/usr/bin/env python3
"""Sign immutable, previously published Maven artifacts; never build or upload them.

The project root must contain the release-version reactor POMs. Inputs are read
from Git objects at the explicitly pinned repository commit, not its worktree.
MAVEN_GPG_PASSPHRASE, when present, is passed through stdin, never argv.
"""

import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

_SPEC = importlib.util.spec_from_file_location(
    'central_bundle_verifier', Path(__file__).with_name('verify-central-bundle.py'))
_VERIFIER = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_VERIFIER)


def git(repository, *arguments):
    result = subprocess.run(['git', '-C', str(repository), *arguments],
                            capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError('Cannot read pinned Git objects: ' + result.stderr.decode(errors='replace').strip())
    return result.stdout


def published_files(repository, commit, project_root, version=None):
    """Return verified immutable artifact bytes for the entire release reactor."""
    if not re.fullmatch(r'[0-9a-fA-F]{40}', commit):
        raise ValueError('A full 40-character repository commit SHA is required')
    if git(repository, 'cat-file', '-t', commit).strip() != b'commit':
        raise ValueError('Pinned object is not a commit')
    modules = _VERIFIER.reactor(project_root)
    if version is not None:
        for stem, _ in modules:
            actual_version = stem.rsplit('/', 2)[1]
            if actual_version != version:
                raise ValueError(f'Reactor version {actual_version} does not match requested version {version}: {stem}')
    required, patterns = set(), []
    for stem, packaging in modules:
        required.add(stem + '.pom')
        if packaging == 'jar':
            required.update(stem + suffix for suffix in ('.jar', '-sources.jar', '-javadoc.jar'))
        patterns.append(re.escape(stem) + r'(?:-[A-Za-z0-9][A-Za-z0-9_.-]*)?\.(?:jar|pom)')
    pattern = re.compile('(?:' + '|'.join(patterns) + ')$')
    tree = {}
    for record in git(repository, 'ls-tree', '-rz', commit).split(b'\0'):
        if not record:
            continue
        metadata, name = record.split(b'\t', 1)
        mode, kind, oid = metadata.decode('ascii').split()
        tree[name.decode('utf-8')] = (mode, kind, oid)
    missing = required - tree.keys()
    if missing:
        raise ValueError('Missing reactor artifact: ' + sorted(missing)[0])

    def read(name):
        if name not in tree:
            raise ValueError('Missing published checksum: ' + name)
        mode, kind, oid = tree[name]
        if mode not in ('100644', '100755') or kind != 'blob':
            raise ValueError('Not a regular published file: ' + name)
        data = git(repository, 'cat-file', 'blob', oid)
        actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if actual != oid:
            raise ValueError('Git blob checksum mismatch: ' + name)
        return data

    artifacts = {}
    for name in sorted(tree):
        if not pattern.fullmatch(name):
            continue
        data = read(name)
        checksum = read(name + '.sha1').decode('ascii').strip().lower()
        if checksum != hashlib.sha1(data).hexdigest():
            raise ValueError('Invalid published SHA1 checksum: ' + name)
        artifacts[name] = data
    return artifacts


def sign(payload, signature, fingerprint):
    """Create and verify a detached signature by exactly the requested key."""
    if not re.fullmatch(r'(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})', fingerprint):
        raise ValueError('A full signing-key fingerprint is required')
    fingerprint = fingerprint.upper()
    environment = os.environ.copy()
    passphrase = environment.pop('MAVEN_GPG_PASSPHRASE', None)
    command = ['gpg', '--no-options', '--batch', '--yes', '--no-auto-key-retrieve',
               '--local-user', fingerprint + '!', '--armor', '--detach-sign',
               '--output', str(signature)]
    if passphrase is not None:
        if '\n' in passphrase or '\r' in passphrase:
            raise ValueError('GPG passphrase must not contain a line break')
        command.extend(['--pinentry-mode', 'loopback', '--passphrase-fd', '0'])
    command.append(str(payload))
    result = subprocess.run(command, input=(passphrase + '\n').encode() if passphrase is not None else b'',
                            env=environment, capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError('GPG signing failed; check the signing key and passphrase')
    result = subprocess.run(['gpg', '--no-options', '--batch', '--no-auto-key-retrieve',
                             '--status-fd', '1', '--verify', str(signature), str(payload)],
                            env=environment, capture_output=True, timeout=30)
    valid = [line.split()[2] for line in result.stdout.decode('ascii', errors='replace').splitlines()
             if line.startswith('[GNUPG:] VALIDSIG ')]
    if result.returncode or valid != [fingerprint]:
        raise ValueError('GPG signature does not verify with the exact expected signing fingerprint')


def bundle(repository, commit, project_root, fingerprint, output, version=None):
    """Publish an output ZIP atomically only after all input/signature checks pass."""
    if not re.fullmatch(r'(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})', fingerprint):
        raise ValueError('A full signing-key fingerprint is required')
    artifacts = published_files(repository, commit, project_root, version)
    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.central-bundle-', dir=output.parent) as directory:
        directory = Path(directory)
        archive_path = directory / 'bundle.zip'
        with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in artifacts.items():
                payload, signature = directory / 'artifact', directory / 'artifact.asc'
                payload.write_bytes(data)
                signature.unlink(missing_ok=True)
                sign(payload, signature, fingerprint)
                archive.writestr(name, data)
                archive.writestr(name + '.asc', signature.read_bytes())
                for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                    archive.writestr(name + '.' + algorithm, hashlib.new(algorithm, data).hexdigest())
        # The shared verifier checks the whole reactor, sidecars and archive paths.
        # Each signature has already been cryptographically checked with its exact key.
        _VERIFIER.verify(archive_path, project_root, False)
        os.replace(archive_path, output)
    print(f'Created signed bundle: {output} (repository commit {commit})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--project-root', required=True, type=Path)
    parser.add_argument('--version', help='Require every reactor module to have exactly this release version')
    parser.add_argument('--fingerprint')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check-only', action='store_true', help='Verify all immutable inputs without using GPG or creating a ZIP')
    args = parser.parse_args()
    if not args.check_only and (not args.fingerprint or not args.output):
        parser.error('--fingerprint and --output are required unless --check-only is used')
    try:
        if args.check_only:
            artifacts = published_files(args.repository, args.commit, args.project_root, args.version)
            print(f'Verified {len(artifacts)} published artifacts ({sum(map(len, artifacts.values()))} bytes) at {args.commit}')
        else:
            bundle(args.repository, args.commit, args.project_root, args.fingerprint, args.output, args.version)
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(f'Published release bundle failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
