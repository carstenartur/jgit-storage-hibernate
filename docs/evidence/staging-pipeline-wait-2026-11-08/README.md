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
