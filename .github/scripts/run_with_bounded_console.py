#!/usr/bin/env python3
"""Retain complete command output and emit only a byte-bounded UTF-8 console tail."""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def run(command, log, console_bytes=16_384, console=None):
    if not command or console_bytes <= 0:
        raise ValueError("a command and a positive console byte limit are required")
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
    exit_code = result.returncode if result.returncode >= 0 else 128 - result.returncode
    size = log.stat().st_size
    header = f"Command exit status {exit_code}; retained {size} raw output bytes.\n".encode("ascii")
    budget = max(0, console_bytes - len(header) - 1)
    with log.open("rb") as output:
        output.seek(max(0, size - budget))
        tail = output.read(budget)
    # Invalid UTF-8 replacement may expand. Enforce the byte bound AFTER replacement,
    # then discard any incomplete leading code point introduced by the final slice.
    tail = tail.decode("utf-8", errors="replace").encode("utf-8")
    tail = tail[-budget:].decode("utf-8", errors="ignore").encode("utf-8") if budget else b""
    rendered = (header + tail + b"\n")[:console_bytes]
    standard_console = console is None
    console = sys.stdout.buffer if standard_console else console
    try:
        console.write(rendered)
        console.flush()
    except (OSError, ValueError):
        # A closed Actions console must not replace Maven's actual result.
        if standard_console:
            try:
                # Prevent Python's shutdown flush from turning the saved exit code into 120.
                with open(os.devnull, "wb") as discarded:
                    os.dup2(discarded.fileno(), console.fileno())
            except (OSError, ValueError):
                pass
    return exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--console-bytes", type=int, default=16_384)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    raise SystemExit(run(command, args.log, args.console_bytes))


if __name__ == "__main__":
    main()
