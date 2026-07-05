#!/usr/bin/env python3
"""Update citation and software metadata for release or snapshot versions."""

from __future__ import annotations

import argparse
import json
import re
from datetime import date
from pathlib import Path


def replace(pattern: str, replacement: str, text: str, *, flags: int = 0) -> str:
    updated, count = re.subn(pattern, replacement, text, flags=flags)
    if count == 0:
        raise SystemExit(f"Pattern not found: {pattern}")
    return updated


def update_citation_cff(version: str, release: bool, today: str) -> None:
    path = Path("CITATION.cff")
    text = path.read_text(encoding="utf-8")
    text = replace(r'^version: ".*"$', f'version: "{version}"', text, flags=re.MULTILINE)
    text = re.sub(r'^date-released: .*\n', "", text, flags=re.MULTILINE)
    if release:
        text = text.replace('version: "' + version + '"\n', 'version: "' + version + '"\n' + f'date-released: "{today}"\n')
    path.write_text(text, encoding="utf-8")


def update_citation_md(version: str, release: bool, today: str) -> None:
    path = Path("CITATION.md")
    text = path.read_text(encoding="utf-8")
    text = replace(r'Version [0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?\.', f'Version {version}.', text)
    text = replace(r'version = \{[^}]+\}', f'version = {{{version}}}', text)
    text = re.sub(r'^\s*date\s+= \{[^}]+\},\n', "", text, flags=re.MULTILINE)
    if release:
        text = text.replace(f'  version = {{{version}}},\n', f'  version = {{{version}}},\n  date    = {{{today}}},\n')
    path.write_text(text, encoding="utf-8")


def update_json(path_name: str, version: str, release: bool, today: str, date_key: str) -> None:
    path = Path(path_name)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["version"] = version
    if release:
        data[date_key] = today
    else:
        data.pop(date_key, None)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    today = date.today().isoformat()

    update_citation_cff(args.version, args.release, today)
    update_citation_md(args.version, args.release, today)
    update_json(".zenodo.json", args.version, args.release, today, "publication_date")
    update_json("codemeta.json", args.version, args.release, today, "datePublished")


if __name__ == "__main__":
    main()
