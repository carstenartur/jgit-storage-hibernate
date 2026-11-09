# Native staging wait diagnostics

Production remains synchronous. These runs locate lifecycle waits and do not
qualify staged-transfer or full Git-publication performance. Issue
[#189](https://github.com/carstenartur/jgit-storage-hibernate/issues/189) remains
subject to its complete publication and non-regression criteria.

## Minimal diagnostic

[Run 42537024048, attempt 1](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42537024048)
completed successfully at source
[`586e8088c8d849c9245ac178414156fa0301cda2`](https://github.com/carstenartur/jgit-storage-hibernate/commit/586e8088c8d849c9245ac178414156fa0301cda2),
tree `aa2ba94b1a7577845b0144beb4db69f0570c0b39`. Its only cell used a 16 MiB
payload, 32 MiB window, one writer, runner/ext4 staging, no injected RTT, no
warm-up and one repetition per mode. The forked JVM retained the 4 GiB heap.

The downloaded artifact ID was `13638839520`; its ZIP SHA-256 matched GitHub's
reported digest:
`eb8329d9d2faa0c7367d8f00379cd3b546575c552aebdeed831f4d13070101a6`.
The complete downloaded artifact passed the connection privacy scanner again.
Its raw environment and runner status both name the exact source commit. The
normal evidence qualifier rejects its `diagnostic-only` measurement boundary.
The [provenance record](minimal/provenance.json) retains archive entry sizes and
hashes alongside the smaller diagnostic records committed here.

Maven plus the native test completed in **28.743 seconds**. The first-to-last
checkpoint interval was **14.740 seconds**. Every provider, telemetry, publication,
reopen, rollback and close phase returned. The first scheduled stack capture was
at 60 seconds, so this successful run produced no thread dump.

| Checkpoint scope | Elapsed seconds | Interpretation |
| --- | ---: | --- |
| PostgreSQL startup | 8.088 | Container startup within the native test |
| Three successful mode trials, combined | 3.584 | Includes provider, telemetry, publication, reopen and close |
| Six injected-failure trials, combined | 1.997 | Reader and JDBC directions for each mode |
| Entire native checkpoint interval | 14.740 | Includes setup and container close |
| Entire Maven diagnostic command | 28.743 | Includes startup outside the checkpoint interval |

The scopes overlap; the last two rows are inclusive totals. The original
timestamped [checkpoints](minimal/phase-checkpoints.tsv) and derived
[phase durations](minimal/phase-durations.json) preserve the detailed ordering.
The first successful mode took 2.601 seconds, and the next two took about
0.49 seconds each. This cold-start variation is another reason these diagnostic
durations are excluded from performance qualification.

### Concrete logging observation

The native log contains 12,625,415 bytes in 281 lines. Six PostgreSQL batch-warning
lines each contain 2,097,420 characters, including a complete binary parameter
encoded as hexadecimal. The three expected JDBC failures are each reported twice.
Surefire XML repeats approximately 12.6 MB of that output. The
[log-volume record](minimal/log-volume.json) retains exact line numbers and sizes
without duplicating the random payloads.

The original evidence workflow sends native output through `tee` to the Actions
console; this diagnostic redirects native stdout to a file. At three repetitions,
the observed failure logging would produce approximately 36 MiB of these warnings.
Console backpressure is therefore a concrete hypothesis to investigate. Log volume
alone does not establish the cause of the earlier wait.

## Diagnostic with the original repetition counts

[Run 42537813528, attempt 1](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42537813528)
completed successfully at source
[`bc1adbc5e3d5739d1c5fe5cc0929242a4038c4d9`](https://github.com/carstenartur/jgit-storage-hibernate/commit/bc1adbc5e3d5739d1c5fe5cc0929242a4038c4d9),
tree `5890b353e6af8fd50d1e521a1580df40d13438f1`. It restored the original one
warm-up round and three retained repetitions for the same cell and 4 GiB heap.
It also included the wrapper's reviewed cleanup of surviving descendants after
every leader exit. The benchmark retained the original failure probe.

Artifact `13640281773` had verified ZIP SHA-256
`c5b1d4ec96aed19d2d832147b9413e83220756ecd692620ae1711a8476594e0d`.
The complete downloaded artifact passed the privacy scanner, and its status and
raw environment matched the expected source and repetition counts. The ordinary
performance qualifier rejected the diagnostic-only boundary. The
[provenance record](original-rounds/provenance.json),
[raw checkpoints](original-rounds/phase-checkpoints.tsv),
[status](original-rounds/diagnostic-status.json) and
[phase durations](original-rounds/phase-durations.json) retain the comparison.

| Observation | Minimal diagnostic | Original-round diagnostic |
| --- | ---: | ---: |
| Warm-up rounds / repetitions per mode | 0 / 1 | 1 / 3 |
| Entire Maven command, seconds | 28.743 | 37.844 |
| Native checkpoint interval, seconds | 14.740 | 22.926 |
| PostgreSQL startup, seconds | 8.088 | 8.755 |
| Warm-up mode trials combined, seconds | None | 3.658 |
| Retained mode trials combined, seconds | 3.584 | 4.042 |
| Injected-failure trials combined, seconds | 1.997 | 5.250 |
| Native output bytes | 12,625,415 | 37,821,317 |
| Surefire XML bytes | 12,624,993 | 37,820,895 |
| Native output lines | 281 | 605 |
| Multi-MiB PostgreSQL warning lines | 6 | 18 |
| Thread captures | 0 | 0 |

Every one of the second run's 385 phase checkpoints completed. The command exited
zero before the first 60-second stack-capture interval; no process-group cleanup
signal was needed. Its eighteen large warning lines were each 2,097,420 characters,
with exact positions retained in [log-volume.json](original-rounds/log-volume.json).
The original repetition counts therefore completed with file-directed output.
This narrows the investigation, but it does not identify a blocked stack in the
separate original matrix run or establish a performance comparison.

## Normal workflow capture correction

The normal evidence workflow now sends complete build and native stdout/stderr
directly to their existing artifact files. After each command exits, the capture
helper emits a valid UTF-8 console tail capped at **16,384 bytes, including its
status header**. The cap is applied after malformed-byte replacement, so neither
a multi-MiB line nor UTF-8 expansion can bypass it. The child exit status is
preserved, including when the console pipe closes during output or Python's
shutdown. The existing always-run privacy scan and upload gate remain in place.

The executable regression uses the actual build and native shell commands with
real child processes producing a large single line, multibyte text and invalid
UTF-8. Before the correction, all four command/exit-status cases emitted 2,100,617
console bytes. Afterwards, those cases preserve every raw byte and exit status
while respecting the 16 KiB cap. A real closed-pipe regression also failed with
an incorrect exit 120 before the shutdown handling was corrected, then passed
with the child's exit 7. These are capture tests, not benchmark evidence.

The next matrix source also carries the independent first-flush timing correction.
Its rollback rows must include monotonic first-flush and failure-detection
chronology. The successful diagnostic rows above retain their original source
and are not retroactively qualified under that correction.

## Boundaries and separate evidence

Original native run
[42534328623](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42534328623)
used source `a8d6f491de538cd91eeb11b8f1f68749bf3566e1`. Its source and eventual
outcome remain independent; a successful shorter diagnostic cannot qualify an
incomplete or interrupted original cell.

The minimal diagnostic uses the original failure probe. A separate review found
that prefetched read failure could be detected before the first write completed.
This diagnostic's successful completion does not validate that rollback timing
boundary. The independent ordering correction adds an interruptible first-flush
gate and retained monotonic chronology; it is deliberately absent from this run.
