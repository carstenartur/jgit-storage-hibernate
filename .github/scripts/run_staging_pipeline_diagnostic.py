#!/usr/bin/env python3
"""Run one diagnostic command with retained JVM stacks and a bounded process-group exit."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


def dump_threads(group, output, sequence, budget=20):
    deadline = time.monotonic() + max(.01, budget)
    directory = output / "thread-dumps"
    directory.mkdir(exist_ok=True)
    report = directory / f"capture-{sequence}.txt"
    with report.open("w") as sink:
        try:
            jcmd = shutil.which("jcmd")
            if not jcmd:
                sink.write("jcmd unavailable\n")
                return
            listing = subprocess.run([jcmd, "-l"], capture_output=True, text=True,
                                     timeout=min(10, max(.01, deadline - time.monotonic())), check=True)
            candidates = []
            for line in listing.stdout.splitlines():
                try:
                    pid = int(line.split(maxsplit=1)[0])
                    if os.getpgid(pid) == group:
                        candidates.append(pid)
                except (ValueError, IndexError, ProcessLookupError, PermissionError):
                    continue
            sink.write(f"JVM process IDs in diagnostic group: {candidates}\n")
            for pid in candidates[:4]:
                if time.monotonic() >= deadline:
                    sink.write("Thread capture budget exhausted\n")
                    break
                name = directory / f"capture-{sequence}-pid-{pid}.txt"
                fallback = False
                with name.open("w") as stack:
                    try:
                        result = subprocess.run([jcmd, str(pid), "Thread.print", "-l"],
                                                stdout=stack, stderr=subprocess.STDOUT,
                                                timeout=min(10, max(.01, deadline - time.monotonic())))
                        sink.write(f"Thread.print for {pid}: exit {result.returncode}\n")
                        fallback = result.returncode != 0
                    except subprocess.TimeoutExpired:
                        sink.write(f"Thread.print for {pid}: capture deadline reached\n")
                        fallback = True
                if fallback:
                    os.kill(pid, signal.SIGQUIT)
                    sink.write(f"SIGQUIT fallback for {pid}; JVM dump goes to native.log\n")
                    time.sleep(.1)
        except (OSError, subprocess.SubprocessError) as failure:
            sink.write(f"Thread capture failed: {type(failure).__name__}\n")


def signal_group(group, signum):
    try:
        os.killpg(group, signum)
        return True
    except ProcessLookupError:
        return False


def run(command, output, timeout=180, dump_after=60, grace=10):
    if not command or timeout <= 0 or dump_after <= 0 or grace <= 0:
        raise ValueError("command and positive timeout, dump interval and grace are required")
    output.mkdir(parents=True, exist_ok=True)
    started_at = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    timed_out = False
    termination = None
    sequence = 0
    next_dump = started + dump_after
    with (output / "native.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        group = process.pid
        try:
            while process.poll() is None:
                now = time.monotonic()
                if now >= started + timeout:
                    timed_out = True
                    sequence += 1
                    dump_threads(group, output, sequence)
                    termination = "SIGTERM"
                    signal_group(group, signal.SIGTERM)
                    try:
                        process.wait(timeout=grace)
                    except subprocess.TimeoutExpired:
                        termination = "SIGKILL"
                        signal_group(group, signal.SIGKILL)
                        process.wait(timeout=5)
                    break
                if now >= next_dump:
                    sequence += 1
                    dump_threads(group, output, sequence, min(20, started + timeout - now))
                    next_dump = time.monotonic() + dump_after
                try:
                    process.wait(timeout=min(.25, max(.01, started + timeout - time.monotonic())))
                except subprocess.TimeoutExpired:
                    pass
        except BaseException:
            signal_group(group, signal.SIGKILL)
            process.wait(timeout=5)
            raise
        finally:
            # The leader can exit before its children, including on success. This
            # group belongs to the new session; preserve the already reaped leader's
            # status while stopping any remaining members without an unbounded wait.
            cleanup_signal = "SIGKILL" if signal_group(group, signal.SIGKILL) else None
            status = dict(schema_version=1, diagnostic_only=True, started_at=started_at,
                          finished_at=datetime.now(timezone.utc).isoformat(),
                          elapsed_seconds=time.monotonic() - started, timed_out=timed_out,
                          return_code=124 if timed_out else process.returncode,
                          command_return_code=process.returncode, termination_signal=termination,
                          process_group_cleanup_signal=cleanup_signal,
                          thread_capture_count=sequence,
                          source_commit=os.environ.get("JGIT_EVIDENCE_SOURCE_SHA", "unrecorded"))
            (output / "diagnostic-status.json").write_text(json.dumps(status, indent=2) + "\n")
    return 124 if timed_out else process.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    parser.add_argument("--dump-after-seconds", type=float, default=60)
    parser.add_argument("--kill-grace-seconds", type=float, default=10)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    raise SystemExit(run(command, args.output, args.timeout_seconds,
                         args.dump_after_seconds, args.kill_grace_seconds))


if __name__ == "__main__":
    main()
