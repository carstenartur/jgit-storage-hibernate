#!/usr/bin/env python3
"""Verify the retained data using the same summarizer and native histogram parser."""

import csv
from hashlib import sha256
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parent
HARNESS = ROOT / 'harness'
SIZES = ('100000', '1000000')


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def check_integrity():
    manifest = dict(line.split('  ', 1)[::-1]
                    for line in (ROOT / 'SHA256SUMS').read_text().splitlines())
    for name, expected in manifest.items():
        path = ROOT / name
        if digest(path) != expected:
            raise ValueError(f'Digest mismatch for {name}')
    normalized = '@STUDY_ROOT@/experiment/'
    for size in SIZES:
        source_digests = (ROOT / 'results' / size / 'provenance.txt').read_text().splitlines()
        for line in source_digests:
            if normalized not in line or '  ' not in line:
                continue
            expected, original = line.split('  ', 1)
            relative = original.removeprefix(normalized)
            if relative == 'cap-overlay.jar':
                continue
            if relative == 'results/1000000/protocol.md':
                relative = 'million-protocol.md'
            if digest(HARNESS / relative) != expected:
                raise ValueError(f'Measured Java source or protocol differs for {relative}')
    for path in (ROOT / 'results' / '100000').glob('threshold1-*/probe-sha256.txt'):
        expected = path.read_text().split()[0]
        if digest(HARNESS / 'threshold/src/RankedScrollMemoryProbe.java') != expected:
            raise ValueError('Measured threshold source differs')


def copy_histograms(destination):
    expected = set()
    for csv_path in (ROOT / 'results').rglob('retained-hits.csv'):
        with csv_path.open(newline='') as stream:
            for row in csv.DictReader(stream):
                folder = csv_path.parent.relative_to(ROOT / 'results')
                expected.add(str(folder / (row['phase'] + '.histogram.txt')))
    seen = set()
    with tarfile.open(ROOT / 'histograms.tar.xz', 'r:xz') as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            if name in seen or name not in expected or not member.isfile():
                raise ValueError(f'Unexpected histogram archive member {member.name}')
            content = archive.extractfile(member).read()
            (destination / name).write_bytes(content)
            seen.add(name)
    if seen != expected:
        raise ValueError('Histogram archive is incomplete')
    return len(seen)


def check_class_origins():
    cells = list((ROOT / 'results').rglob('classpath.txt'))
    for cp_path in cells:
        folder = cp_path.parent
        entries = cp_path.read_text().strip().split(':')
        origin_files = ('runtime-search-origins.txt', 'scroll-class-origin.txt')
        origins = [line.split(' source: file:', 1)[1]
                   for name in origin_files for line in (folder / name).read_text().splitlines()]
        overlay_expected = folder.name.rsplit('-', 1)[-1] in {'4096', '16384'}
        if len(origins) != 6 or not all(origin in entries for origin in origins):
            raise ValueError(f'Class origin differs from recorded classpath at {folder.name}')
        if origins[-1].endswith('/cap-overlay.jar') != overlay_expected:
            raise ValueError(f'Cap overlay origin differs at {folder.name}')
    return len(cells)


def main():
    check_integrity()
    cells = check_class_origins()
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        script = work / 'experiment' / 'summarize.py'
        validator = work / 'consumer' / '.github' / 'scripts' / 'validate_ranked_scroll_evidence.py'
        script.parent.mkdir()
        validator.parent.mkdir(parents=True)
        shutil.copyfile(HARNESS / 'summarize.py', script)
        shutil.copyfile(HARNESS / 'upstream-validator.py', validator)
        data = script.parent / 'results'
        shutil.copytree(ROOT / 'results', data)
        histograms = copy_histograms(data)
        for size in SIZES:
            observed = subprocess.check_output([sys.executable, str(script), str(data / size)])
            if observed != (ROOT / 'results' / size / 'summary.csv').read_bytes():
                raise ValueError(f'Summary differs for {size} documents')
    print(f'Validated two summaries, {histograms} native histograms and {cells} classpath/origin records.')


if __name__ == '__main__':
    main()
